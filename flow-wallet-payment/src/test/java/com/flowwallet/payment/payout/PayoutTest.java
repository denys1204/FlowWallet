package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.flowwallet.payment.payout.PayoutStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-10T13:00:00Z");
    private static final Instant ACTED_AT = Instant.parse("2026-10-10T14:00:00Z");

    private static final Map<String, BiFunction<Payout, Instant, Boolean>> MUTATORS = Map.of(
            "recordTransfer", (payout, now) -> payout.recordTransfer("acct_1", "tr_1", now),
            "returnBeforeTransfer", (payout, now) -> payout.returnBeforeTransfer(PayoutReason.TERMS_REFUSED, now),
            "recordPayout", (payout, now) -> payout.recordPayout("po_1", now),
            "markPaid", Payout::markPaid,
            "startReversal", (payout, now) -> payout.startReversal(PayoutReason.PAYOUT_FAILED, "no_account", now),
            "recordReversal", (payout, now) -> payout.recordReversal("trr_1", now),
            "needAttention", (payout, now) -> payout.needAttention(AttentionCause.UNKNOWN_LIMIT, "d", now),
            "requeue", Payout::requeue,
            "operatorReturn", Payout::operatorReturn,
            "operatorReverse", Payout::operatorReverse
    );

    /**
     * ADR 0035's table, as ADR 0037 extends it: the move each mutator may make from a status, or null when it changes
     * nothing. A payout in {@code NEEDS_ATTENTION} goes back to its step, an operator may return it unless it came
     * from {@code TRANSFERRED}, and may send it to its reversal only when it came from there.
     */
    private static PayoutStatus expected(String mutator, PayoutStatus from, PayoutStatus attentionFrom) {
        return switch (mutator) {
            case "recordTransfer" -> from == ACCEPTED ? TRANSFERRED : null;
            case "returnBeforeTransfer" -> from == ACCEPTED ? RETURNED : null;
            case "recordPayout" -> from == TRANSFERRED ? PAYOUT_CREATED : null;
            case "markPaid" -> from == PAYOUT_CREATED ? PAID : null;
            case "startReversal" -> from == TRANSFERRED || from == PAYOUT_CREATED || from == PAID ? REVERSING : null;
            case "recordReversal" -> from == REVERSING ? RETURNED : null;
            case "needAttention" -> from.isExecutorStep() ? NEEDS_ATTENTION : null;
            case "requeue" -> from == NEEDS_ATTENTION ? attentionFrom : null;
            case "operatorReturn" -> from == NEEDS_ATTENTION && attentionFrom != TRANSFERRED ? RETURNED : null;
            case "operatorReverse" -> from == NEEDS_ATTENTION && attentionFrom == TRANSFERRED ? REVERSING : null;
            default -> throw new IllegalArgumentException(mutator);
        };
    }

    /**
     * A payout in any status, with the ids its status needs, its attempts spent and a claim held.
     */
    private static Payout.PayoutBuilder rowIn(PayoutStatus status, PayoutStatus attentionFrom) {
        return Payout.builder()
                .transactionReference("ref-1")
                .userId("alice")
                .amount(new BigDecimal("75.0000"))
                .currency("PLN")
                .status(status)
                .stripeAccountId(status == ACCEPTED ? null : "acct_1")
                .stripeTransferId(status == ACCEPTED ? null : "tr_1")
                .stripePayoutId(status == PAYOUT_CREATED || status == PAID ? "po_1" : null)
                .attentionFrom(attentionFrom)
                .attempts(3)
                .nextAttemptAt(LATER)
                .claimedUntil(LATER);
    }

    private static Payout inStatus(PayoutStatus status, PayoutStatus attentionFrom) {
        return rowIn(status, attentionFrom).build();
    }

    private static Payout inStatus(PayoutStatus status) {
        return inStatus(status, status == NEEDS_ATTENTION ? REVERSING : null);
    }

    static Stream<Arguments> everyMutatorFromEveryStatus() {
        return MUTATORS.keySet().stream().sorted().flatMap(mutator -> Stream.concat(
                Arrays.stream(PayoutStatus.values())
                        .filter(from -> from != NEEDS_ATTENTION)
                        .map(from -> Arguments.of(mutator, from, null)),
                Stream.of(ACCEPTED, TRANSFERRED, REVERSING).map(step -> Arguments.of(mutator, NEEDS_ATTENTION, step))
        ));
    }

    @ParameterizedTest(name = "{0} from {1} {2}")
    @MethodSource("everyMutatorFromEveryStatus")
    void onlyTheTransitionsOfTheTableChangeAPayoutAndEachFollowsTheDueRules(
            String mutator,
            PayoutStatus from,
            PayoutStatus attentionFrom
    ) {
        // Guards a move outside ADR 0035's table, such as a Paid event on a payout being reversed or an operator
        // return of a transfer that may still be paid out, a mutator that reports a change it did not make, and a
        // move that keeps the claim or, into an executor step, keeps the old backoff and spent attempts.
        Payout payout = inStatus(from, attentionFrom);
        PayoutStatus target = expected(mutator, from, attentionFrom);

        boolean changed = MUTATORS.get(mutator).apply(payout, NOW);

        assertThat(changed).isEqualTo(target != null);
        if (target == null) {
            assertThat(payout.getStatus()).isEqualTo(from);
            assertThat(payout.getClaimedUntil()).isEqualTo(LATER);
            assertThat(payout.getAttempts()).isEqualTo(3);
            assertThat(payout.getAttentionFrom()).isEqualTo(attentionFrom);
            assertThat(payout.getReasonCode()).isNull();
            return;
        }
        assertThat(payout.getStatus()).isEqualTo(target);
        assertThat(payout.getClaimedUntil()).isNull();
        assertThat(payout.getNextAttemptAt()).isEqualTo(target.isExecutorStep() ? NOW : LATER);
        assertThat(payout.getAttempts()).isEqualTo(target.isExecutorStep() ? 0 : 3);
    }

    @Test
    void anOperatorCannotReturnAPayoutWhoseTransferMayStillBePaidOut() {
        // Guards paying both the bank and the wallet: from TRANSFERRED the money sits in the connected account and a
        // payout of it may be live, and a reversal can still bring it back.
        Payout payout = inStatus(NEEDS_ATTENTION, TRANSFERRED);

        assertThat(payout.operatorReturn(NOW)).isFalse();

        assertThat(payout.getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(payout.getReasonCode()).isNull();
    }

    @Test
    void anAcceptedCommandIsDueAtOnceUnderTheFirstKey() {
        // Guards an accepted row with no next_attempt_at, which the executor's scan for due rows would never find.
        Payout payout = Payout.accepted("ref-1", "alice", new BigDecimal("75.0000"), "PLN", NOW);

        assertThat(payout.getStatus()).isEqualTo(ACCEPTED);
        assertThat(payout.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(payout.getAttempts()).isZero();
        assertThat(payout.getKeyGen()).isZero();
    }

    @Test
    void aMoveIntoAnExecutorStepMakesTheRowDueAndStartsItsWaitAgain() {
        // Guards a row moved into a step by the webhook or a requeue staying parked behind an old backoff, or
        // inheriting attempts that would send it to NEEDS_ATTENTION after one try.
        Payout payout = inStatus(ACCEPTED);

        payout.recordTransfer("acct_1", "tr_1", NOW);

        assertThat(payout.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(payout.getAttempts()).isZero();
        assertThat(payout.getClaimedUntil()).isNull();
        assertThat(payout.getTransferredAt()).isEqualTo(NOW);
        assertThat(payout.getStripeAccountId()).isEqualTo("acct_1");
    }

    @Test
    void aReversalRecordsWhyAndStripesFailureCode() {
        // Guards the Failed event's reasonCode and failure code, which the wallet shows and an operator reads.
        Payout payout = inStatus(PAID);

        payout.startReversal(PayoutReason.PAYOUT_FAILED, "no_account", NOW);

        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.PAYOUT_FAILED);
        assertThat(payout.getFailureCode()).isEqualTo("no_account");
        assertThat(payout.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void aPayoutNeedingAttentionRemembersItsStepAndARequeueStartsItAgain() {
        // Guards a requeue that sends the row nowhere, or to a step it was never in, and one that keeps the spent
        // attempts, which would send it straight back to NEEDS_ATTENTION.
        Payout payout = inStatus(REVERSING);

        payout.needAttention(AttentionCause.UNKNOWN_LIMIT, "timed out", NOW);
        assertThat(payout.getAttentionFrom()).isEqualTo(REVERSING);

        payout.requeue(LATER);

        assertThat(payout.getStatus()).isEqualTo(REVERSING);
        assertThat(payout.getAttentionFrom()).isNull();
        assertThat(payout.getNextAttemptAt()).isEqualTo(LATER);
        assertThat(payout.getAttempts()).isZero();
    }

    @Test
    void anOperatorReturnRecordsItsOwnReason() {
        // Guards an operator's return reaching the wallet with the reason of the failure before it, which would hide
        // that the platform took the loss.
        Payout payout = inStatus(NEEDS_ATTENTION);

        payout.operatorReturn(NOW);

        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.OPERATOR_RETURNED);
        assertThat(payout.getAttentionFrom()).isNull();
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "TRANSFERRED", "REVERSING"})
    void needAttentionRecordsItsCauseAndDetailWithTheStep(PayoutStatus step) {
        // Guards a hand-over that forgets why the row waits, which the operator's list has to show, or that stores
        // the cause under a step other than the one the row left.
        Payout payout = inStatus(step);

        boolean changed = payout.needAttention(AttentionCause.MISMATCH, "transfer tr_9 has other terms", NOW);

        assertThat(changed).isTrue();
        assertThat(payout.getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(payout.getAttentionFrom()).isEqualTo(step);
        assertThat(payout.getAttentionCause()).isEqualTo(AttentionCause.MISMATCH);
        assertThat(payout.getAttentionDetail()).isEqualTo("transfer tr_9 has other terms");
    }

    @Test
    void anAttentionDetailLongerThanTheColumnIsCutToIt() {
        // Guards a Stripe error message longer than attention_detail reaching the insert, which Postgres refuses, so
        // the row could not be handed over. The cut keeps the start, where the error type is, and a detail that
        // fits the column exactly stays whole.
        String fitting = "a".repeat(Payout.ATTENTION_DETAIL_LENGTH);
        Payout tooLong = inStatus(ACCEPTED);
        Payout exact = inStatus(ACCEPTED);

        tooLong.needAttention(AttentionCause.UNKNOWN_LIMIT, fitting + "b", NOW);
        exact.needAttention(AttentionCause.UNKNOWN_LIMIT, fitting, NOW);

        assertThat(tooLong.getAttentionDetail()).isEqualTo(fitting);
        assertThat(exact.getAttentionDetail()).isEqualTo(fitting);
        assertThat(Payout.ATTENTION_DETAIL_LENGTH).as("the width of attention_detail").isEqualTo(255);
    }

    @Test
    void anAttentionDetailCutBetweenTheHalvesOfASurrogatePairEndsBeforeThePair() {
        // Guards a cut that leaves a lone high surrogate at the end of the column, which the driver stores as '?', so
        // the operator would read a mangled last character. Character 255, at index 254, opens a pair here, so the
        // cut stops one character short of the column.
        String start = "a".repeat(Payout.ATTENTION_DETAIL_LENGTH - 1);
        Payout payout = inStatus(ACCEPTED);

        payout.needAttention(AttentionCause.UNKNOWN_LIMIT, start + "\uD83D\uDE00 and more", NOW);

        String stored = payout.getAttentionDetail();
        assertThat(stored).isEqualTo(start).hasSize(254);
        assertThat(stored.chars().noneMatch(unit -> Character.isSurrogate((char) unit))).isTrue();
    }

    @Test
    void anAttentionDetailCutAfterAWholeSurrogatePairKeepsThePair() {
        // Guards a cut that is too careful: a pair that ends on the last character of the column fits it whole, and
        // dropping it would shorten the detail for nothing.
        String start = "a".repeat(Payout.ATTENTION_DETAIL_LENGTH - 2);
        Payout payout = inStatus(ACCEPTED);

        payout.needAttention(AttentionCause.UNKNOWN_LIMIT, start + "\uD83D\uDE00 and more", NOW);

        assertThat(payout.getAttentionDetail()).isEqualTo(start + "\uD83D\uDE00").hasSize(255);
    }

    @Test
    void anAttentionWithoutADetailStoresNone() {
        // Guards a hand-over with no detail failing on the cut, which would leave the row unable to wait for an
        // operator.
        Payout payout = inStatus(TRANSFERRED);

        payout.needAttention(AttentionCause.DEFECT, null, NOW);

        assertThat(payout.getStatus()).isEqualTo(NEEDS_ATTENTION);
        assertThat(payout.getAttentionCause()).isEqualTo(AttentionCause.DEFECT);
        assertThat(payout.getAttentionDetail()).isNull();
    }

    @Test
    void aHandOverWithoutACauseIsRefused() {
        // Guards a hand-over that leaves the operator's list without a reason, which the column allows only for rows
        // that waited before the cause was stored.
        Payout payout = inStatus(ACCEPTED);

        assertThatThrownBy(() -> payout.needAttention(null, "d", NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cause");
        assertThat(payout.getStatus()).isEqualTo(ACCEPTED);
        assertThat(payout.getAttentionCause()).isNull();
        assertThat(payout.getAttentionFrom()).isNull();
    }

    @ParameterizedTest(name = "{0} from {1}")
    @CsvSource({
            "requeue, ACCEPTED",
            "requeue, TRANSFERRED",
            "requeue, REVERSING",
            "operatorReturn, ACCEPTED",
            "operatorReturn, REVERSING",
            "operatorReverse, TRANSFERRED"
    })
    void everyExitFromNeedsAttentionClearsTheCauseAndDetail(String exit, PayoutStatus step) {
        // Guards a stale cause on a row that no longer waits: payouts_attention_cause_only_when_needed refuses it at
        // the update, and the operator's view would show it as a reason to act.
        Payout payout = inStatus(step);
        payout.needAttention(AttentionCause.UNKNOWN_LIMIT, "timed out", NOW);

        boolean changed = MUTATORS.get(exit).apply(payout, ACTED_AT);

        assertThat(changed).isTrue();
        assertThat(payout.getAttentionFrom()).isNull();
        assertThat(payout.getAttentionCause()).isNull();
        assertThat(payout.getAttentionDetail()).isNull();
    }

    @Test
    void anOperatorReversalSendsAStuckTransferToItsReversalAsAFailedPayout() {
        // Guards a reversal that reaches the wallet without the reason the Failed event reads, that carries a failure
        // code Stripe never gave, that loses the transfer the reversal needs, or that waits behind the old backoff
        // and spent attempts.
        Payout payout = rowIn(TRANSFERRED, null).failureCode("stale_code").build();
        payout.needAttention(AttentionCause.UNKNOWN_LIMIT, "timed out", NOW);

        boolean changed = payout.operatorReverse(ACTED_AT);

        assertThat(changed).isTrue();
        assertThat(payout.getStatus()).isEqualTo(REVERSING);
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.PAYOUT_FAILED);
        assertThat(payout.getFailureCode()).isNull();
        assertThat(payout.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(payout.getStripeAccountId()).isEqualTo("acct_1");
        assertThat(payout.getNextAttemptAt()).isEqualTo(ACTED_AT);
        assertThat(payout.getAttempts()).isZero();
        assertThat(payout.getClaimedUntil()).isNull();
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "TRANSFERRED", "REVERSING"})
    void aRequeueKeepsTheKeyGenerationAndTheStepsFirstAttemptTime(PayoutStatus step) {
        // Guards a requeue that skips the lookup or moves to a fresh key after an unknown outcome, which could
        // create a second transfer, payout or reversal at Stripe for one withdrawal.
        Instant transferTried = NOW.minusSeconds(900);
        Instant payoutTried = NOW.minusSeconds(600);
        Instant reversalTried = NOW.minusSeconds(300);
        Payout payout = rowIn(step, null)
                .keyGen(2)
                .transferAttemptedAt(transferTried)
                .payoutAttemptedAt(payoutTried)
                .reversalAttemptedAt(reversalTried)
                .build();
        payout.needAttention(AttentionCause.UNKNOWN_LIMIT, "timed out", NOW);

        payout.requeue(ACTED_AT);

        assertThat(payout.getStatus()).isEqualTo(step);
        assertThat(payout.getKeyGen()).isEqualTo(2);
        assertThat(payout.getTransferAttemptedAt()).isEqualTo(transferTried);
        assertThat(payout.getPayoutAttemptedAt()).isEqualTo(payoutTried);
        assertThat(payout.getReversalAttemptedAt()).isEqualTo(reversalTried);
        assertThat(payout.attemptedBefore(ACTED_AT)).isTrue();
    }

    @Test
    void aDeferredAttemptCountsItAndReleasesTheClaim() {
        // Guards a retry that never counts towards the limit for an unknown outcome, and a claim held until its lease
        // lapses, which would delay the next try by the whole lease.
        Payout payout = inStatus(ACCEPTED);

        payout.deferAttempt(LATER);

        assertThat(payout.getAttempts()).isEqualTo(4);
        assertThat(payout.getNextAttemptAt()).isEqualTo(LATER);
        assertThat(payout.getClaimedUntil()).isNull();
        assertThat(payout.getStatus()).isEqualTo(ACCEPTED);
    }

    @Test
    void aNewKeyGenerationFollowsADefiniteRefusal() {
        // Guards a retry under the same key, which Stripe answers with the refusal it stored.
        Payout payout = inStatus(TRANSFERRED);

        payout.raiseKeyGen();

        assertThat(payout.getKeyGen()).isEqualTo(1);
    }

    @Test
    void everyStatusAndReasonFitsItsColumn() {
        // Guards a constant added later that the VARCHAR(20) status or VARCHAR(32) reason column would refuse.
        assertThat(Arrays.stream(PayoutStatus.values()).map(Enum::name)).allMatch(name -> name.length() <= 20);
        assertThat(Arrays.stream(PayoutReason.values()).map(Enum::name)).allMatch(name -> name.length() <= 32);
    }

    @Test
    void attemptedBeforeComparesTheStepsFirstClaimWithThisOne() {
        // Guards a step that skips its lookup after a crashed try, or looks up on every first try.
        Payout payout = inStatus(ACCEPTED);

        assertThat(payout.attemptedBefore(NOW)).isFalse();
        payout = Payout.builder()
                .transactionReference("ref-1")
                .status(TRANSFERRED)
                .payoutAttemptedAt(NOW)
                .build();
        assertThat(payout.attemptedBefore(NOW)).isFalse();
        assertThat(payout.attemptedBefore(LATER)).isTrue();
        Payout reversing = Payout.builder()
                .transactionReference("ref-1")
                .status(REVERSING)
                .reversalAttemptedAt(NOW)
                .build();
        assertThat(reversing.attemptedBefore(LATER)).isTrue();
    }

    @Test
    void onlyAnExecutorStepHasAnAttemptTime() {
        // Guards a step judged by another step's attempt time.
        Payout payout = inStatus(PAID);

        assertThatThrownBy(() -> payout.attemptedBefore(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releasingAClaimChangesNothingElse() {
        // Guards a released claim counting as a try or moving the row's next attempt.
        Payout payout = inStatus(ACCEPTED);
        int attempts = payout.getAttempts();

        payout.releaseClaim();

        assertThat(payout.getClaimedUntil()).isNull();
        assertThat(payout.getAttempts()).isEqualTo(attempts);
        assertThat(payout.getNextAttemptAt()).isEqualTo(LATER);
        assertThat(payout.getStatus()).isEqualTo(ACCEPTED);
    }
}
