package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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

    private static final Map<String, BiFunction<Payout, Instant, Boolean>> MUTATORS = Map.of(
            "recordTransfer", (payout, now) -> payout.recordTransfer("acct_1", "tr_1", now),
            "returnBeforeTransfer", (payout, now) -> payout.returnBeforeTransfer(PayoutReason.TERMS_REFUSED, now),
            "recordPayout", (payout, now) -> payout.recordPayout("po_1", now),
            "markPaid", Payout::markPaid,
            "startReversal", (payout, now) -> payout.startReversal(PayoutReason.PAYOUT_FAILED, "no_account", now),
            "recordReversal", (payout, now) -> payout.recordReversal("trr_1", now),
            "needAttention", Payout::needAttention,
            "requeue", Payout::requeue,
            "operatorReturn", Payout::operatorReturn
    );

    /**
     * ADR 0035's table, as the move each mutator may make from a status, or null when it changes nothing. A payout in
     * {@code NEEDS_ATTENTION} goes back to its step, and an operator may return it unless it came from
     * {@code TRANSFERRED}.
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
            default -> throw new IllegalArgumentException(mutator);
        };
    }

    /**
     * A payout in any status, with the ids its status needs, its attempts spent and a claim held.
     */
    private static Payout inStatus(PayoutStatus status, PayoutStatus attentionFrom) {
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
                .claimedUntil(LATER)
                .build();
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

        payout.needAttention(NOW);
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
