package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PayoutStoreTest {
    private static final String REF = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String OTHER_REF = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final Instant CLAIMED_AT = Instant.parse("2026-10-10T09:00:00.123456Z");
    private static final Instant TOKEN = Instant.parse("2026-10-10T09:02:00.123456Z");
    private static final Instant NOW = Instant.parse("2026-10-10T09:00:30Z");
    private static final Instant PAID_AT = Instant.parse("2026-10-08T15:00:00Z");
    private static final Instant ACTED_AT = Instant.parse("2026-10-10T09:05:00Z");
    private static final long READ_VERSION = 4L;
    private static final long MOVED_VERSION = 5L;

    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final PayoutAccountRepository accounts = mock(PayoutAccountRepository.class);
    private final PayoutOutboxService outbox = mock(PayoutOutboxService.class);
    private final PayoutStore store = new PayoutStore(payouts, accounts, outbox);

    private static Payout.PayoutBuilder row(PayoutStatus status) {
        Payout.PayoutBuilder builder = Payout.builder()
                .id(1L)
                .transactionReference(REF)
                .userId(USER)
                .amount(new BigDecimal("75.0000"))
                .currency("PLN")
                .status(status)
                .keyGen(2)
                .attempts(3)
                .nextAttemptAt(NOW)
                .claimedUntil(TOKEN);
        if (status != PayoutStatus.ACCEPTED) {
            builder.stripeAccountId("acct_1")
                    .stripeTransferId("tr_1")
                    .transferredAt(NOW)
                    .transferAttemptedAt(CLAIMED_AT.minusSeconds(60));
        }
        if (status == PayoutStatus.REVERSING) {
            builder.reasonCode(PayoutReason.PAYOUT_FAILED);
        }
        return builder;
    }

    private static PayoutAccount readyAccount() {
        PayoutAccount account = PayoutAccount.builder()
                .id(7L)
                .userId(USER)
                .accountKey("key-1")
                .tosAcceptedAt(Instant.EPOCH)
                .bankState(BankAccountState.NOT_SET)
                .build();
        account.recordStripeAccount("acct_1");
        account.recordBankAccount("btok_1", "ba_1", "2874", "pln", BankAccountState.READY);
        return account;
    }

    private ClaimedPayout claimed(Payout payout) {
        return ClaimedPayout.of(payout, CLAIMED_AT, Optional.of(readyAccount()));
    }

    private Payout recorded(Payout payout, StepOutcome outcome) {
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));
        assertThat(store.record(claimed(payout), outcome, NOW)).isEqualTo(RecordResult.RECORDED);
        return payout;
    }

    /**
     * A row as a settlement finds it: with the payout id its status implies, the time a paid row was paid, and
     * {@code REVERSING} as the step a row in {@code NEEDS_ATTENTION} came from.
     */
    private static Payout.PayoutBuilder settling(PayoutStatus status) {
        Payout.PayoutBuilder builder = row(status);
        if (status != PayoutStatus.ACCEPTED && status != PayoutStatus.TRANSFERRED) {
            builder.stripePayoutId("po_1");
        }
        if (status == PayoutStatus.PAID) {
            builder.paidAt(PAID_AT);
        }
        if (status == PayoutStatus.NEEDS_ATTENTION) {
            builder.attentionFrom(PayoutStatus.REVERSING);
        }
        return builder;
    }

    private static Payout created() {
        return settling(PayoutStatus.PAYOUT_CREATED).build();
    }

    /**
     * The payout as Stripe holds it, on the row's terms: the amount in two decimals against the row's four, a failure
     * code when it failed, and the stored bank account as its destination, which Stripe names whatever the status.
     */
    private static ProviderPayout found(ProviderPayoutStatus status) {
        String failureCode = status == ProviderPayoutStatus.FAILED ? "account_closed" : null;
        return new ProviderPayout("po_1", status, failureCode, REF, new BigDecimal("75.00"), "PLN", "ba_1");
    }

    /**
     * A failed payout with its own id, reference, amount, currency or destination.
     */
    private static ProviderPayout failed(
            String id,
            String reference,
            String amount,
            String currency,
            String destinationId
    ) {
        return new ProviderPayout(
                id,
                ProviderPayoutStatus.FAILED,
                "account_closed",
                reference,
                new BigDecimal(amount),
                currency,
                destinationId
        );
    }

    /**
     * Lets settle find the row as it looks: by its payout id when it has one, and by its reference.
     */
    private Payout stored(Payout payout) {
        if (payout.getStripePayoutId() != null) {
            when(payouts.lockByStripePayoutId(payout.getStripePayoutId())).thenReturn(Optional.of(payout));
        }
        when(payouts.lockByTransactionReference(REF)).thenReturn(Optional.of(payout));
        return payout;
    }

    /**
     * The user's ready bank account {@code ba_1}, as a failure's reset locks it by the payout's connected account.
     */
    private PayoutAccount storedBank() {
        PayoutAccount account = readyAccount();
        when(accounts.lockByStripeAccountId("acct_1")).thenReturn(Optional.of(account));
        return account;
    }

    /**
     * The outcomes a cell of the settlement table names: {@code adopt} records the payout on a row still in
     * {@code TRANSFERRED}, {@code paid} marks it paid, {@code reverse} starts the reversal; {@code -} is none.
     */
    private static List<StepOutcome> outcomes(String names, ProviderPayoutStatus stripe) {
        if (names.equals("-")) {
            return List.of();
        }
        return Arrays.stream(names.split("\\+"))
                .map(name -> switch (name) {
                    case "adopt" -> (StepOutcome) new StepOutcome.PayoutCreated("po_1", true);
                    case "paid" -> new StepOutcome.Paid();
                    case "reverse" -> new StepOutcome.ReversalStarted(
                            PayoutReason.PAYOUT_FAILED,
                            found(stripe).failureCode()
                    );
                    default -> throw new IllegalArgumentException(name);
                })
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "TRANSFERRED", "REVERSING"})
    void eachStepRunsItsOwnClaimUpdate(PayoutStatus step) {
        // Guards a claim that marks another step attempted, so a later try would skip the lookup it needs.
        Payout payout = row(step).build();
        when(payouts.claimTransfer(1L, CLAIMED_AT, TOKEN)).thenReturn(1);
        when(payouts.claimPayout(1L, CLAIMED_AT, TOKEN)).thenReturn(1);
        when(payouts.claimReversal(1L, CLAIMED_AT, TOKEN)).thenReturn(1);
        when(payouts.findById(1L)).thenReturn(Optional.of(payout));

        assertThat(store.claim(new DuePayout(1L, step), CLAIMED_AT, TOKEN)).isPresent();

        switch (step) {
            case ACCEPTED -> verify(payouts).claimTransfer(1L, CLAIMED_AT, TOKEN);
            case TRANSFERRED -> verify(payouts).claimPayout(1L, CLAIMED_AT, TOKEN);
            default -> verify(payouts).claimReversal(1L, CLAIMED_AT, TOKEN);
        }
    }

    @Test
    void aLostClaimReadsNothing() {
        // Guards a step run on a row another executor holds or that moved since it was selected.
        when(payouts.claimTransfer(1L, CLAIMED_AT, TOKEN)).thenReturn(0);

        assertThat(store.claim(new DuePayout(1L, PayoutStatus.ACCEPTED), CLAIMED_AT, TOKEN)).isEmpty();
        verify(payouts, never()).findById(any());
    }

    @Test
    void theFirstClaimOfAStepIsNotAnEarlierAttempt() {
        // Guards a lookup on every first try, read because the claim itself wrote the attempt time.
        Payout payout = row(PayoutStatus.ACCEPTED).transferAttemptedAt(CLAIMED_AT).build();

        assertThat(claimed(payout).earlierAttempt()).isFalse();
    }

    @Test
    void aClaimThatFindsAnEarlierAttemptTimeIsALaterAttempt() {
        // Guards a try after a crash creating without first looking for what the crashed try made.
        Payout payout = row(PayoutStatus.ACCEPTED).transferAttemptedAt(CLAIMED_AT.minusSeconds(300)).build();

        assertThat(claimed(payout).earlierAttempt()).isTrue();
    }

    @Test
    void theClaimLeavesTheUserIdBehind() {
        // Guards the user id, which spends the user's balance, reaching a log line through a logged claim.
        assertThat(claimed(row(PayoutStatus.TRANSFERRED).build()).toString()).doesNotContain(USER);
    }

    @Test
    void aBankOnAnotherConnectedAccountThanTheTransfersIsNotReady() {
        // Guards a payout on an account other than the one the transfer's money sits in.
        Payout payout = row(PayoutStatus.TRANSFERRED).stripeAccountId("acct_other").build();

        assertThat(claimed(payout).bankReady()).isFalse();
    }

    @Test
    void aRecordUnderAnotherTokenChangesNothingAndWritesNoEvent() {
        // Guards two executors both recording one step after a lease lapsed: only the current claim's owner may.
        Payout payout = row(PayoutStatus.ACCEPTED).build();
        ClaimedPayout claim = claimed(payout);
        Payout reclaimed = row(PayoutStatus.ACCEPTED).claimedUntil(TOKEN.plusSeconds(300)).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(reclaimed));

        assertThat(store.record(claim, new StepOutcome.Returned(PayoutReason.TRANSFER_REFUSED, "x"), NOW))
                .isEqualTo(RecordResult.TAKEN_BY_CLAIM);
        assertThat(reclaimed.getStatus()).isEqualTo(PayoutStatus.ACCEPTED);
        verifyNoInteractions(outbox);
    }

    @Test
    void aRecordAfterATransitionReportsTheRowTaken() {
        // Guards a second event for a payout the webhook already moved: every transition clears the token.
        ClaimedPayout claim = claimed(row(PayoutStatus.TRANSFERRED).build());
        Payout moved = row(PayoutStatus.PAYOUT_CREATED).stripePayoutId("po_1").claimedUntil(null).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(moved));

        assertThat(store.record(claim, new StepOutcome.PayoutCreated("po_1", false), NOW))
                .isEqualTo(RecordResult.TAKEN_BY_TRANSITION);
        verifyNoInteractions(outbox);
    }

    @Test
    void aTransferIsRecordedWithItsAccountAndNoEvent() {
        // Guards a transfer recorded without the account the payout and the reversal need.
        Payout payout = recorded(
                row(PayoutStatus.ACCEPTED).build(),
                new StepOutcome.Transferred("acct_1", "tr_1", false)
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.TRANSFERRED);
        assertThat(payout.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(payout.getClaimedUntil()).isNull();
        verifyNoInteractions(outbox);
    }

    @Test
    void aReturnBeforeTheTransferWritesItsReturnedEvent() {
        // Guards money returned in Payment Service and never credited back in the wallet.
        Payout payout = recorded(
                row(PayoutStatus.ACCEPTED).build(),
                new StepOutcome.Returned(PayoutReason.PLATFORM_FUNDS_SHORT, "balance_insufficient")
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.RETURNED);
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.PLATFORM_FUNDS_SHORT);
        verify(outbox).publishReturned(payout, NOW);
    }

    @Test
    void aPayoutIsRecordedWithoutAnEvent() {
        // Guards a Paid event before Stripe has paid anything: only the webhook and the reconciler send it.
        Payout payout = recorded(row(PayoutStatus.TRANSFERRED).build(), new StepOutcome.PayoutCreated("po_1", false));

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAYOUT_CREATED);
        verifyNoInteractions(outbox);
    }

    @Test
    void aFailedPayoutFoundIsRecordedAndReversedInOneTransaction() {
        // Guards a failed payout recorded as live, which would wait for a webhook that already came and went.
        Payout payout = recorded(
                row(PayoutStatus.TRANSFERRED).build(),
                new StepOutcome.FailedPayoutAdopted("po_failed", "no_account", null)
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getStripePayoutId()).isEqualTo("po_failed");
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.PAYOUT_FAILED);
        assertThat(payout.getFailureCode()).isEqualTo("no_account");
        verify(outbox).publishFailed(payout, NOW);
    }

    @Test
    void aStartedReversalWritesItsFailedEvent() {
        // Guards the wallet showing PAID for money that is coming back.
        Payout payout = recorded(
                row(PayoutStatus.TRANSFERRED).build(),
                new StepOutcome.ReversalStarted(PayoutReason.BANK_ACCOUNT_NOT_SET, null)
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        verify(outbox).publishFailed(payout, NOW);
    }

    @Test
    void aBankRejectionReversesThePayoutAndWritesItsFailedEvent() {
        // Guards the wallet still showing PAID for a payout whose money is coming back.
        when(accounts.lockById(7L)).thenReturn(Optional.of(readyAccount()));
        Payout payout = recorded(
                row(PayoutStatus.TRANSFERRED).build(),
                new StepOutcome.BankAccountRejected("bank_account_unusable", 7L, "ba_1")
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.BANK_ACCOUNT_REJECTED);
        assertThat(payout.getFailureCode()).isEqualTo("bank_account_unusable");
        verify(outbox).publishFailed(payout, NOW);
    }

    @Test
    void aBankRejectionThatNamesNoBankAccountResetsNone() {
        // Guards a refusal Stripe stored for an older bank account resetting the one the user set since.
        recorded(row(PayoutStatus.TRANSFERRED).build(), new StepOutcome.BankAccountRejected("x", 7L, null));

        verify(accounts, never()).lockById(any());
    }

    @Test
    void attentionWithoutARaiseKeepsTheGeneration() {
        // Guards an unknown outcome or a defect moving the key on, which could create a second object after requeue.
        Payout payout = recorded(
                row(PayoutStatus.ACCEPTED).build(),
                new StepOutcome.Attention(AttentionCause.UNKNOWN_LIMIT, false, "timeout")
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.NEEDS_ATTENTION);
        assertThat(payout.getKeyGen()).isEqualTo(2);
    }

    @Test
    void anAttentionOutcomeStoresItsCauseAndDetailOnTheRow() {
        // Guards a hand-over that reaches the row without why it waits, which leaves the operator's list with a
        // reason to guess.
        Payout payout = recorded(
                row(PayoutStatus.REVERSING).build(),
                new StepOutcome.Attention(AttentionCause.LIVE_PAYOUT, false, "payout po_1 is in transit")
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.NEEDS_ATTENTION);
        assertThat(payout.getAttentionFrom()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getAttentionCause()).isEqualTo(AttentionCause.LIVE_PAYOUT);
        assertThat(payout.getAttentionDetail()).isEqualTo("payout po_1 is in transit");
        verifyNoInteractions(outbox);
    }

    @Test
    void aPostponementWaitsWithoutCountingAnAttempt() {
        // Guards a shutdown either counting a try against the limit or making the row due before Stripe can have
        // finished the cut-short request.
        Instant next = NOW.plusSeconds(60);
        Payout payout = recorded(row(PayoutStatus.TRANSFERRED).build(), new StepOutcome.Postponed(next, "interrupted"));

        assertThat(payout.getAttempts()).isEqualTo(3);
        assertThat(payout.getNextAttemptAt()).isEqualTo(next);
        assertThat(payout.getClaimedUntil()).isNull();
    }

    @Test
    void aBankRejectionResetsOnlyTheBankAccountItSaw() {
        // Guards resetting a bank account the user set after the claim, which Stripe has not refused.
        PayoutAccount same = readyAccount();
        when(accounts.lockById(7L)).thenReturn(Optional.of(same));
        recorded(row(PayoutStatus.TRANSFERRED).build(), new StepOutcome.BankAccountRejected("x", 7L, "ba_1"));
        assertThat(same.getBankState()).isEqualTo(BankAccountState.NOT_SET);

        PayoutAccount replaced = readyAccount();
        when(accounts.lockById(7L)).thenReturn(Optional.of(replaced));
        recorded(row(PayoutStatus.TRANSFERRED).build(), new StepOutcome.BankAccountRejected("x", 7L, "ba_old"));
        assertThat(replaced.getBankState()).isEqualTo(BankAccountState.READY);
    }

    @Test
    void aReversalReturnsThePayoutWithItsReturnedEvent() {
        // Guards money back on the platform that the wallet never credits.
        Payout payout = recorded(row(PayoutStatus.REVERSING).build(), new StepOutcome.Reversed("trr_1"));

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.RETURNED);
        assertThat(payout.getStripeReversalId()).isEqualTo("trr_1");
        verify(outbox).publishReturned(payout, NOW);
    }

    @Test
    void anUnknownRetryCountsTheAttemptAndKeepsTheGeneration() {
        // Guards a fresh key after an unknown outcome, and an attempt limit that never comes.
        Instant next = NOW.plusSeconds(60);
        Payout payout = recorded(row(PayoutStatus.ACCEPTED).build(), new StepOutcome.RetryUnknown(next, "timeout"));

        assertThat(payout.getKeyGen()).isEqualTo(2);
        assertThat(payout.getAttempts()).isEqualTo(4);
        assertThat(payout.getNextAttemptAt()).isEqualTo(next);
        assertThat(payout.getClaimedUntil()).isNull();
    }

    @Test
    void aRefusedRetryRaisesTheGenerationAndCountsTheAttempt() {
        // Guards a retry under the old key, which Stripe answers with the stored refusal for a day.
        Payout payout = recorded(
                row(PayoutStatus.TRANSFERRED).build(),
                new StepOutcome.RetryRefused(NOW.plusSeconds(7200), "balance_insufficient")
        );

        assertThat(payout.getKeyGen()).isEqualTo(3);
        assertThat(payout.getAttempts()).isEqualTo(4);
    }

    @Test
    void attentionAfterARefusedReversalSavesTheRaisedGeneration() {
        // Guards a requeue whose first try gets the stored refusal back.
        Payout payout = recorded(
                row(PayoutStatus.REVERSING).build(),
                new StepOutcome.Attention(AttentionCause.REVERSAL_REFUSED, true, "x")
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.NEEDS_ATTENTION);
        assertThat(payout.getAttentionFrom()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getKeyGen()).isEqualTo(3);
        verifyNoInteractions(outbox);
    }

    @Test
    void aReleaseClearsTheClaimWithoutCountingAnAttempt() {
        // Guards a try that made no Stripe call counting towards the limit.
        Payout payout = recorded(row(PayoutStatus.ACCEPTED).build(), new StepOutcome.Released("no room"));

        assertThat(payout.getClaimedUntil()).isNull();
        assertThat(payout.getAttempts()).isEqualTo(3);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.ACCEPTED);
    }

    @Test
    void aTransitionRefusedUnderItsOwnClaimIsADefect() {
        // Guards an outcome decided for one status silently applied to another, with no event written.
        Payout payout = row(PayoutStatus.TRANSFERRED).build();
        ClaimedPayout claim = claimed(payout);
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));

        assertThatThrownBy(() -> store.record(claim, new StepOutcome.Transferred("acct_1", "tr_2", false), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REF);
    }

    /**
     * ADR 0036's settlement of every row status against every Stripe status, written out by hand: the outcomes, the
     * status the row ends in and how the settlement is reported. {@code ACCEPTED} has no connected account yet, so
     * {@code settle} refuses it as differing terms and its kind is {@code -}.
     */
    private static final String[] SETTLEMENT_TABLE = {
            "ACCEPTED,        PENDING,      -,             ACCEPTED,        -",
            "ACCEPTED,        IN_TRANSIT,   -,             ACCEPTED,        -",
            "ACCEPTED,        PAID,         -,             ACCEPTED,        -",
            "ACCEPTED,        FAILED,       -,             ACCEPTED,        -",
            "ACCEPTED,        CANCELED,     -,             ACCEPTED,        -",
            "ACCEPTED,        UNRECOGNIZED, -,             ACCEPTED,        -",
            "TRANSFERRED,     PENDING,      adopt,         PAYOUT_CREATED,  MOVED",
            "TRANSFERRED,     IN_TRANSIT,   adopt,         PAYOUT_CREATED,  MOVED",
            "TRANSFERRED,     PAID,         adopt+paid,    PAID,            MOVED",
            "TRANSFERRED,     FAILED,       adopt+reverse, REVERSING,       MOVED",
            "TRANSFERRED,     CANCELED,     adopt+reverse, REVERSING,       MOVED",
            "TRANSFERRED,     UNRECOGNIZED, adopt,         PAYOUT_CREATED,  MOVED",
            "PAYOUT_CREATED,  PENDING,      -,             PAYOUT_CREATED,  REPEATED",
            "PAYOUT_CREATED,  IN_TRANSIT,   -,             PAYOUT_CREATED,  REPEATED",
            "PAYOUT_CREATED,  PAID,         paid,          PAID,            MOVED",
            "PAYOUT_CREATED,  FAILED,       reverse,       REVERSING,       MOVED",
            "PAYOUT_CREATED,  CANCELED,     reverse,       REVERSING,       MOVED",
            "PAYOUT_CREATED,  UNRECOGNIZED, -,             PAYOUT_CREATED,  CONTRADICTED",
            "PAID,            PENDING,      -,             PAID,            CONTRADICTED",
            "PAID,            IN_TRANSIT,   -,             PAID,            CONTRADICTED",
            "PAID,            PAID,         -,             PAID,            REPEATED",
            "PAID,            FAILED,       reverse,       REVERSING,       MOVED",
            "PAID,            CANCELED,     reverse,       REVERSING,       MOVED",
            "PAID,            UNRECOGNIZED, -,             PAID,            CONTRADICTED",
            "REVERSING,       PENDING,      -,             REVERSING,       CONTRADICTED",
            "REVERSING,       IN_TRANSIT,   -,             REVERSING,       CONTRADICTED",
            "REVERSING,       PAID,         -,             REVERSING,       CONTRADICTED",
            "REVERSING,       FAILED,       -,             REVERSING,       REPEATED",
            "REVERSING,       CANCELED,     -,             REVERSING,       REPEATED",
            "REVERSING,       UNRECOGNIZED, -,             REVERSING,       CONTRADICTED",
            "RETURNED,        PENDING,      -,             RETURNED,        CONTRADICTED",
            "RETURNED,        IN_TRANSIT,   -,             RETURNED,        CONTRADICTED",
            "RETURNED,        PAID,         -,             RETURNED,        CONTRADICTED",
            "RETURNED,        FAILED,       -,             RETURNED,        REPEATED",
            "RETURNED,        CANCELED,     -,             RETURNED,        REPEATED",
            "RETURNED,        UNRECOGNIZED, -,             RETURNED,        CONTRADICTED",
            "NEEDS_ATTENTION, PENDING,      -,             NEEDS_ATTENTION, CONTRADICTED",
            "NEEDS_ATTENTION, IN_TRANSIT,   -,             NEEDS_ATTENTION, CONTRADICTED",
            "NEEDS_ATTENTION, PAID,         -,             NEEDS_ATTENTION, CONTRADICTED",
            "NEEDS_ATTENTION, FAILED,       -,             NEEDS_ATTENTION, CONTRADICTED",
            "NEEDS_ATTENTION, CANCELED,     -,             NEEDS_ATTENTION, CONTRADICTED",
            "NEEDS_ATTENTION, UNRECOGNIZED, -,             NEEDS_ATTENTION, CONTRADICTED",
    };

    static Stream<Arguments> settlementTable() {
        return Arrays.stream(SETTLEMENT_TABLE).map(row -> {
            String[] cells = Arrays.stream(row.split(",")).map(String::trim).toArray(String[]::new);
            return arguments(
                    PayoutStatus.valueOf(cells[0]),
                    ProviderPayoutStatus.valueOf(cells[1]),
                    cells[2],
                    PayoutStatus.valueOf(cells[3]),
                    cells[4]
            );
        });
    }

    @Test
    void theSettlementTableHasEveryRowStatusWithEveryStripeStatus() {
        // Guards a status added to either enum without a decision in the table below.
        assertThat(settlementTable().map(row -> row.get()[0] + " " + row.get()[1]).distinct())
                .hasSize(PayoutStatus.values().length * ProviderPayoutStatus.values().length);
    }

    @ParameterizedTest(name = "{1} on {0}: {2}")
    @MethodSource("settlementTable")
    void eachRowStatusAndStripeStatusSettlesAsTheTableSays(
            PayoutStatus status,
            ProviderPayoutStatus stripe,
            String outcomes,
            PayoutStatus after,
            String kind
    ) {
        // Guards a Paid event for a payout that failed, a reversal of money that reached the bank, an adoption of a
        // row an operator holds, and an outcome the row's next transition refuses under its lock.
        assertThat(PayoutStore.settlementOf(settling(status).build(), found(stripe)))
                .isEqualTo(outcomes(outcomes, stripe));
    }

    static Stream<Arguments> settlementTableWithAConnectedAccount() {
        return settlementTable().filter(row -> row.get()[0] != PayoutStatus.ACCEPTED);
    }

    @ParameterizedTest(name = "{1} on {0}: {3}, {4}")
    @MethodSource("settlementTableWithAConnectedAccount")
    void eachSettlementEndsReportsAndWritesAsTheTableSays(
            PayoutStatus status,
            ProviderPayoutStatus stripe,
            String outcomes,
            PayoutStatus after,
            String kind
    ) {
        // Guards a second Paid or Failed event for a status the row already shows, a status that cannot move the row
        // reported as applied, and a bank account reset by a canceled payout or kept READY after a failed one.
        Payout payout = stored(settling(status).build());
        PayoutAccount bank = storedBank();

        Settlement settlement = store.settle("acct_1", found(stripe), NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.valueOf(kind));
        assertThat(settlement.from()).isEqualTo(status);
        assertThat(settlement.to()).isEqualTo(after).isEqualTo(payout.getStatus());
        verify(outbox, times(outcomes.contains("paid") ? 1 : 0)).publishPaid(payout, NOW);
        verify(outbox, times(outcomes.contains("reverse") ? 1 : 0)).publishFailed(payout, NOW);
        verifyNoMoreInteractions(outbox);
        assertThat(settlement.bankReset()).isEqualTo(stripe == ProviderPayoutStatus.FAILED);
        assertThat(bank.getBankState()).isEqualTo(
                stripe == ProviderPayoutStatus.FAILED ? BankAccountState.NOT_SET : BankAccountState.READY
        );
    }

    @Test
    void paidMovesCreatedToPaidWithOnePaidEvent() {
        // Guards the wallet never learning that the money reached the bank.
        Payout payout = stored(settling(PayoutStatus.PAYOUT_CREATED).build());

        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.PAID), NOW);

        assertThat(settlement).isEqualTo(new Settlement(
                Settlement.Kind.MOVED,
                REF,
                ProviderPayoutStatus.PAID,
                PayoutStatus.PAYOUT_CREATED,
                PayoutStatus.PAID,
                false,
                null
        ));
        assertThat(payout.getPaidAt()).isEqualTo(NOW);
        assertThat(payout.getClaimedUntil()).isNull();
        verify(outbox).publishPaid(payout, NOW);
        verifyNoMoreInteractions(outbox);
        verifyNoInteractions(accounts);
    }

    @Test
    void paidOnPaidWritesNothing() {
        // Guards a redelivered payout.paid, or the reconciler's look at a paid row, sending a second Paid event or
        // moving paid_at, which starts the period in which the reconciler watches for a late failure.
        Payout payout = stored(settling(PayoutStatus.PAYOUT_CREATED).build());
        store.settle("acct_1", found(ProviderPayoutStatus.PAID), NOW);

        Settlement again = store.settle("acct_1", found(ProviderPayoutStatus.PAID), NOW.plusSeconds(3600));

        assertThat(again.kind()).isEqualTo(Settlement.Kind.REPEATED);
        assertThat(again.to()).isEqualTo(PayoutStatus.PAID);
        assertThat(payout.getPaidAt()).isEqualTo(NOW);
        verify(outbox).publishPaid(payout, NOW);
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void aRedeliveredFailureWritesNoSecondFailedEvent() {
        // Guards a redelivered payout.failed telling the wallet twice that the money is coming back.
        Payout payout = stored(settling(PayoutStatus.PAYOUT_CREATED).build());
        storedBank();
        store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW);

        Settlement again = store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW.plusSeconds(60));

        assertThat(again.kind()).isEqualTo(Settlement.Kind.REPEATED);
        assertThat(again.bankReset()).isFalse();
        verify(outbox).publishFailed(payout, NOW);
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void failedReversesAPaidPayoutHoweverOld() {
        // Guards a payout Stripe fails long after it showed paid leaving the user without the money and the wallet
        // showing PAID: a payout that shows paid can fail days later.
        Payout payout = stored(settling(PayoutStatus.PAID).paidAt(NOW.minus(Duration.ofDays(30))).build());
        storedBank();

        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.MOVED);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.PAYOUT_FAILED);
        assertThat(payout.getFailureCode()).isEqualTo("account_closed");
        verify(outbox).publishFailed(payout, NOW);
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void failedResetsTheStoredBankEvenWhenAlreadyReversing() {
        // Guards a failure that reaches a row the executor already sent to its reversal leaving the bank account
        // READY for the next withdrawal, and that settlement overwriting the row or clearing the reversal's claim.
        Payout payout = stored(settling(PayoutStatus.REVERSING).failureCode("balance_insufficient").build());
        PayoutAccount bank = storedBank();

        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.REPEATED);
        assertThat(settlement.bankReset()).isTrue();
        assertThat(bank.getBankState()).isEqualTo(BankAccountState.NOT_SET);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getFailureCode()).isEqualTo("balance_insufficient");
        assertThat(payout.getClaimedUntil()).isEqualTo(TOKEN);
        verifyNoInteractions(outbox);
    }

    @Test
    void failedToAnotherBankKeepsReady() {
        // Guards a failed payout to a bank account the user has since replaced marking the new one NOT_SET.
        Payout payout = stored(settling(PayoutStatus.PAYOUT_CREATED).build());
        PayoutAccount bank = storedBank();

        Settlement settlement = store.settle("acct_1", failed("po_1", REF, "75.00", "PLN", "ba_old"), NOW);

        assertThat(settlement.bankReset()).isFalse();
        assertThat(bank.getBankState()).isEqualTo(BankAccountState.READY);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
    }

    @Test
    void aFailureThatNamesNoBankAccountResetsNone() {
        // Guards a failure Stripe reports without its destination resetting whichever bank account is stored.
        stored(settling(PayoutStatus.PAYOUT_CREATED).build());

        Settlement settlement = store.settle("acct_1", failed("po_1", REF, "75.00", "PLN", null), NOW);

        assertThat(settlement.bankReset()).isFalse();
        verifyNoInteractions(accounts);
    }

    @Test
    void canceledReversesWithoutResettingTheBank() {
        // Guards a cancellation, which says nothing about the bank account, blocking the user's next withdrawal.
        Payout payout = stored(settling(PayoutStatus.PAYOUT_CREATED).build());

        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.CANCELED), NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.MOVED);
        assertThat(settlement.bankReset()).isFalse();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.PAYOUT_FAILED);
        verify(outbox).publishFailed(payout, NOW);
        verifyNoInteractions(accounts);
    }

    @ParameterizedTest(name = "{0} leaves it {1}")
    @CsvSource({"PENDING, PAYOUT_CREATED", "PAID, PAID", "FAILED, REVERSING"})
    void aTransferredRowFoundByReferenceAdoptsThenAppliesPaidFailedOrPending(
            ProviderPayoutStatus stripe,
            PayoutStatus expected
    ) {
        // Guards a webhook that beats the executor's record being dropped as unknown, or adopting the payout and
        // leaving its paid or failed status for a webhook that already came and went.
        Payout payout = stored(settling(PayoutStatus.TRANSFERRED).build());
        storedBank();

        Settlement settlement = store.settle("acct_1", found(stripe), NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.MOVED);
        assertThat(settlement.from()).isEqualTo(PayoutStatus.TRANSFERRED);
        assertThat(settlement.to()).isEqualTo(expected);
        assertThat(payout.getStatus()).isEqualTo(expected);
        assertThat(payout.getStripePayoutId()).isEqualTo("po_1");
        assertThat(payout.getClaimedUntil()).isNull();
        verify(payouts).lockByTransactionReference(REF);
    }

    @Test
    void aTransferredRowWhosePayoutFailedSendsOneFailedEvent() {
        // Guards the adoption and the failure each sending a Failed event, or the adopted failure resetting no bank
        // account.
        Payout payout = stored(settling(PayoutStatus.TRANSFERRED).build());
        PayoutAccount bank = storedBank();

        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW);

        assertThat(settlement.to()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(settlement.bankReset()).isTrue();
        assertThat(bank.getBankState()).isEqualTo(BankAccountState.NOT_SET);
        assertThat(payout.getReasonCode()).isEqualTo(PayoutReason.PAYOUT_FAILED);
        assertThat(payout.getFailureCode()).isEqualTo("account_closed");
        verify(outbox, times(1)).publishFailed(payout, NOW);
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void paidOnReversingIsContradictedAndWritesNothing() {
        // Guards a Paid event after the Failed one, for money whose reversal is running, and a settlement clearing
        // the claim of the executor that reverses the transfer.
        Payout payout = stored(settling(PayoutStatus.REVERSING).build());

        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.PAID), NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.CONTRADICTED);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(payout.getPaidAt()).isNull();
        assertThat(payout.getClaimedUntil()).isEqualTo(TOKEN);
        verifyNoInteractions(outbox, accounts);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProviderPayoutStatus.class)
    void aNeedsAttentionRowNeverAdopts(ProviderPayoutStatus stripe) {
        // Guards a settlement taking a row out of an operator's hands: ADR 0036 adopts only a row still TRANSFERRED.
        // A failed payout still resets its bank account.
        Payout payout = stored(row(PayoutStatus.NEEDS_ATTENTION).attentionFrom(PayoutStatus.TRANSFERRED).build());
        storedBank();

        Settlement settlement = store.settle("acct_1", found(stripe), NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.CONTRADICTED);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.NEEDS_ATTENTION);
        assertThat(payout.getAttentionFrom()).isEqualTo(PayoutStatus.TRANSFERRED);
        assertThat(payout.getStripePayoutId()).isNull();
        assertThat(settlement.bankReset()).isEqualTo(stripe == ProviderPayoutStatus.FAILED);
        verifyNoInteractions(outbox);
    }

    static Stream<Arguments> differingTerms() {
        return Stream.of(
                arguments("account", created(), "acct_other", found(ProviderPayoutStatus.FAILED)),
                arguments(
                        named("account of a row with no transfer", "account"),
                        settling(PayoutStatus.ACCEPTED).build(),
                        "acct_1",
                        found(ProviderPayoutStatus.FAILED)
                ),
                arguments("amount", created(), "acct_1", failed("po_1", REF, "75.01", "PLN", "ba_1")),
                arguments(
                        named("amount of a payout not adopted yet", "amount"),
                        settling(PayoutStatus.TRANSFERRED).build(),
                        "acct_1",
                        failed("po_1", REF, "7500.00", "PLN", "ba_1")
                ),
                arguments("currency", created(), "acct_1", failed("po_1", REF, "75.00", "EUR", "ba_1")),
                arguments("payout id", created(), "acct_1", failed("po_2", REF, "75.00", "PLN", "ba_1")),
                arguments("reference", created(), "acct_1", failed("po_1", OTHER_REF, "75.00", "PLN", "ba_1")),
                arguments("amount, currency", created(), "acct_1", failed("po_1", REF, "75.01", "EUR", "ba_1"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differingTerms")
    void anyDifferingTermWritesNothingAndResetsNoBank(
            String differences,
            Payout payout,
            String accountId,
            ProviderPayout found
    ) {
        // Guards a verified payout that disagrees with its row moving it, adopting it, sending an event or resetting
        // a bank account, when only an operator can tell which side is wrong.
        stored(payout);
        PayoutStatus before = payout.getStatus();
        String payoutIdBefore = payout.getStripePayoutId();

        Settlement settlement = store.settle(accountId, found, NOW);

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.DIFFERING_TERMS);
        assertThat(settlement.differences()).isEqualTo(differences);
        assertThat(settlement.reference()).isEqualTo(REF);
        assertThat(settlement.to()).isEqualTo(before);
        assertThat(settlement.bankReset()).isFalse();
        assertThat(payout.getStatus()).isEqualTo(before);
        assertThat(payout.getStripePayoutId()).isEqualTo(payoutIdBefore);
        assertThat(payout.getClaimedUntil()).isEqualTo(TOKEN);
        verifyNoInteractions(outbox, accounts);
    }

    @Test
    void anUnknownPayoutWritesNothing() {
        // Guards a payout for a reference this service does not hold touching any row.
        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW);

        assertThat(settlement).isEqualTo(new Settlement(
                Settlement.Kind.UNKNOWN_PAYOUT,
                REF,
                ProviderPayoutStatus.FAILED,
                null,
                null,
                false,
                null
        ));
        verify(payouts).lockByStripePayoutId("po_1");
        verify(payouts).lockByTransactionReference(REF);
        verifyNoInteractions(outbox, accounts);
    }

    @Test
    void aPayoutWithoutAReferenceIsLookedUpOnlyByItsId() {
        // Guards a payout this service did not make, which carries no reference, being matched to a row by anything
        // but its id.
        ProviderPayout foreign = new ProviderPayout(
                "po_9",
                ProviderPayoutStatus.PAID,
                null,
                null,
                new BigDecimal("75.00"),
                "PLN",
                "ba_1"
        );

        assertThat(store.settle("acct_1", foreign, NOW).kind()).isEqualTo(Settlement.Kind.UNKNOWN_PAYOUT);
        verify(payouts, never()).lockByTransactionReference(any());
        verifyNoInteractions(outbox, accounts);
    }

    @Test
    void theExecutorsRecordAfterAnAdoptionIsTakenByTransition() {
        // Guards the executor's own record of a failed payout the webhook already adopted sending a second Failed
        // event: the adoption clears the claim's token, and the row has left the claimed step.
        Payout payout = stored(settling(PayoutStatus.TRANSFERRED).build());
        ClaimedPayout claim = claimed(payout);
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));
        storedBank();
        store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW);

        RecordResult result = store.record(
                claim,
                new StepOutcome.FailedPayoutAdopted("po_1", "account_closed", "ba_1"),
                NOW.plusSeconds(5)
        );

        assertThat(result).isEqualTo(RecordResult.TAKEN_BY_TRANSITION);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        verify(outbox).publishFailed(payout, NOW);
        verifyNoMoreInteractions(outbox);
        verify(accounts, times(1)).lockByStripeAccountId("acct_1");
    }

    @Test
    void aSettlementAfterTheExecutorsRecordIsRepeatedWithoutASecondEvent() {
        // Guards the webhook that lost the race to the executor's record sending a second Failed event or rejecting
        // the payout as a second one: its lookup by payout id ran before the record committed, and its lookup by
        // reference waited on the lock and reads the recorded row.
        Payout payout = stored(settling(PayoutStatus.TRANSFERRED).build());
        PayoutAccount bank = storedBank();
        recorded(payout, new StepOutcome.FailedPayoutAdopted("po_1", "account_closed", "ba_1"));

        Settlement settlement = store.settle("acct_1", found(ProviderPayoutStatus.FAILED), NOW.plusSeconds(5));

        assertThat(settlement.kind()).isEqualTo(Settlement.Kind.REPEATED);
        assertThat(settlement.bankReset()).isFalse();
        assertThat(bank.getBankState()).isEqualTo(BankAccountState.NOT_SET);
        verify(outbox).publishFailed(payout, NOW);
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void aFailedPayoutTheExecutorAdoptsResetsAMatchingBank() {
        // Guards a failed payout the executor finds leaving its bank account READY when no webhook ever arrives, and
        // resetting a bank account the user set since.
        PayoutAccount same = storedBank();
        recorded(
                row(PayoutStatus.TRANSFERRED).build(),
                new StepOutcome.FailedPayoutAdopted("po_failed", "account_closed", "ba_1")
        );
        assertThat(same.getBankState()).isEqualTo(BankAccountState.NOT_SET);

        PayoutAccount replaced = storedBank();
        recorded(
                row(PayoutStatus.TRANSFERRED).build(),
                new StepOutcome.FailedPayoutAdopted("po_failed", "account_closed", "ba_old")
        );
        assertThat(replaced.getBankState()).isEqualTo(BankAccountState.READY);
    }

    @Test
    void aCanceledPayoutTheExecutorAdoptsResetsNoBank() {
        // Guards a cancellation, which says nothing about the bank account, blocking the user's next withdrawal.
        Payout payout = recorded(
                row(PayoutStatus.TRANSFERRED).build(),
                new StepOutcome.FailedPayoutAdopted("po_canceled", null, null)
        );

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REVERSING);
        verify(accounts, never()).lockByStripeAccountId(any());
    }

    @Test
    void theReconcilerClaimReportsWhetherItWon() {
        // Guards two instances both asking Stripe about one row, and a look at a row the webhook moved since the
        // reconciler selected it: the claim's update is conditional on both.
        ReconcilablePayout created = new ReconcilablePayout(1L, REF, PayoutStatus.PAYOUT_CREATED, "acct_1", "po_1");
        Instant lookedAtBefore = NOW.minus(Duration.ofMinutes(15));
        when(payouts.claimForReconciliation(1L, PayoutStatus.PAYOUT_CREATED, NOW, lookedAtBefore)).thenReturn(1, 0);

        assertThat(store.claimForReconciliation(created, NOW, lookedAtBefore)).isTrue();
        assertThat(store.claimForReconciliation(created, NOW, lookedAtBefore)).isFalse();
    }

    @Test
    void theReconcilerReadsItsBatchAsTheFirstPage() {
        // Guards a run that reads more rows than its batch, or skips the least recently looked at on a later page.
        ReconcilablePayout created = new ReconcilablePayout(1L, REF, PayoutStatus.PAYOUT_CREATED, "acct_1", "po_1");
        ReconcilablePayout paid = new ReconcilablePayout(2L, OTHER_REF, PayoutStatus.PAID, "acct_1", "po_2");
        Instant lookedAtBefore = NOW.minus(Duration.ofHours(6));
        Instant paidAfter = NOW.minus(Duration.ofDays(10));
        when(payouts.findCreatedToReconcile(lookedAtBefore, PageRequest.of(0, 50))).thenReturn(List.of(created));
        when(payouts.findPaidToReconcile(paidAfter, lookedAtBefore, PageRequest.of(0, 49))).thenReturn(List.of(paid));

        assertThat(store.findCreatedToReconcile(lookedAtBefore, 50)).containsExactly(created);
        assertThat(store.findPaidToReconcile(paidAfter, lookedAtBefore, 49)).containsExactly(paid);
    }

    /**
     * A row waiting for an operator since the step it came from, at the version the operator's Stripe check read.
     */
    private static Payout.PayoutBuilder waiting(PayoutStatus from) {
        return row(from)
                .status(PayoutStatus.NEEDS_ATTENTION)
                .attentionFrom(from)
                .attentionCause(AttentionCause.UNKNOWN_LIMIT)
                .attentionDetail("timed out")
                .claimedUntil(null)
                .version(READ_VERSION);
    }

    private static PayoutSnapshot snapshotOf(OperatorWrite write) {
        return Objects.requireNonNull(write.payout());
    }

    private OperatorWrite operatorWrite(String write, long seenVersion) {
        return switch (write) {
            case "operatorReturn" -> store.operatorReturn(1L, seenVersion, ACTED_AT);
            case "operatorReverse" -> store.operatorReverse(1L, seenVersion, ACTED_AT);
            default -> throw new IllegalArgumentException(write);
        };
    }

    /**
     * Notes the status and reason the row shows when each event is queued, since the event is built from them.
     */
    private List<String> queuedEvents(Payout payout) {
        List<String> queued = new ArrayList<>();
        doAnswer(call -> {
            queued.add("Returned: " + payout.getStatus() + ", " + payout.getReasonCode());
            return null;
        }).when(outbox).publishReturned(any(), any());
        doAnswer(call -> {
            queued.add("Failed: " + payout.getStatus() + ", " + payout.getReasonCode());
            return null;
        }).when(outbox).publishFailed(any(), any());
        return queued;
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "TRANSFERRED", "REVERSING"})
    void aRequeueReturnsAWaitingPayoutToItsStepAndWritesNoEvent(PayoutStatus step) {
        // Guards a requeue that leaves the row waiting, that moves it to another step than it came from, that keeps
        // the old backoff or the spent attempts, or that tells the wallet of a payout that has not ended.
        Payout payout = waiting(step).build();
        when(payouts.lockByTransactionReference(REF)).thenReturn(Optional.of(payout));

        OperatorWrite write = store.requeue(REF, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.APPLIED);
        assertThat(snapshotOf(write).status()).isEqualTo(step);
        assertThat(snapshotOf(write).attentionFrom()).isNull();
        assertThat(payout.getStatus()).isEqualTo(step);
        assertThat(payout.getAttentionCause()).isNull();
        assertThat(payout.getAttentionDetail()).isNull();
        assertThat(payout.getNextAttemptAt()).isEqualTo(ACTED_AT);
        assertThat(payout.getAttempts()).isZero();
        assertThat(payout.getKeyGen()).isEqualTo(2);
        verifyNoInteractions(outbox, accounts);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PayoutStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "NEEDS_ATTENTION")
    void aRequeueOfAPayoutInAnyOtherStatusChangesNothing(PayoutStatus status) {
        // Guards a repeated requeue, after a lost answer or by a second operator, moving a row the executor has
        // claimed since or one that already ended.
        Payout payout = row(status).build();
        when(payouts.lockByTransactionReference(REF)).thenReturn(Optional.of(payout));

        OperatorWrite write = store.requeue(REF, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.NOT_WAITING);
        assertThat(snapshotOf(write).status()).isEqualTo(status);
        assertThat(payout.getStatus()).isEqualTo(status);
        assertThat(payout.getClaimedUntil()).isEqualTo(TOKEN);
        assertThat(payout.getAttempts()).isEqualTo(3);
        assertThat(payout.getNextAttemptAt()).isEqualTo(NOW);
        verifyNoInteractions(outbox, accounts);
    }

    @Test
    void aRequeueOfAnUnknownReferenceFindsNothing() {
        // Guards a requeue of a reference no row holds answering as if a row had moved.
        OperatorWrite write = store.requeue(OTHER_REF, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.NOT_FOUND);
        assertThat(write.payout()).isNull();
        verify(payouts).lockByTransactionReference(OTHER_REF);
        verifyNoInteractions(outbox, accounts);
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = PayoutStatus.class, names = {"ACCEPTED", "REVERSING"})
    void anOperatorReturnWritesOneReturnedEventInTheTransactionThatMovesTheRow(PayoutStatus step) {
        // Guards a return that credits the wallet for a row that did not move, a move without its event, a second
        // event, and an event queued before the move, which would carry no reason.
        Payout payout = waiting(step).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));
        List<String> queued = queuedEvents(payout);

        OperatorWrite write = store.operatorReturn(1L, READ_VERSION, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.APPLIED);
        assertThat(snapshotOf(write).status()).isEqualTo(PayoutStatus.RETURNED);
        assertThat(snapshotOf(write).reasonCode()).isEqualTo(PayoutReason.OPERATOR_RETURNED);
        assertThat(payout.getAttentionFrom()).isNull();
        assertThat(queued).containsExactly("Returned: RETURNED, OPERATOR_RETURNED");
        verify(outbox).publishReturned(payout, ACTED_AT);
        verifyNoMoreInteractions(outbox);
        verifyNoInteractions(accounts);
    }

    @Test
    void anOperatorReturnUnderAnOlderVersionChangesNothingAndWritesNoEvent() {
        // Guards a return decided on a Stripe check of a row that has since been requeued, claimed and handed over
        // again: the check described another state, and the wallet would be credited on it.
        Payout payout = waiting(PayoutStatus.ACCEPTED).version(MOVED_VERSION).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));

        OperatorWrite write = store.operatorReturn(1L, READ_VERSION, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.CHANGED);
        assertThat(snapshotOf(write).reference()).isEqualTo(REF);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.NEEDS_ATTENTION);
        assertThat(payout.getAttentionFrom()).isEqualTo(PayoutStatus.ACCEPTED);
        assertThat(payout.getReasonCode()).isNull();
        verifyNoInteractions(outbox, accounts);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PayoutStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "NEEDS_ATTENTION")
    void anOperatorReturnOfAPayoutThatLeftNeedsAttentionChangesNothing(PayoutStatus status) {
        // Guards a return of a row that was requeued and has run on since, with the version the check read: a row
        // out of NEEDS_ATTENTION is refused as not waiting, and neither a status nor an event changes.
        Payout payout = row(status).version(MOVED_VERSION).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));

        OperatorWrite write = store.operatorReturn(1L, READ_VERSION, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.NOT_WAITING);
        assertThat(snapshotOf(write).status()).isEqualTo(status);
        assertThat(payout.getStatus()).isEqualTo(status);
        assertThat(payout.getClaimedUntil()).isEqualTo(TOKEN);
        verifyNoInteractions(outbox, accounts);
    }

    @Test
    void anOperatorReversalWritesOneFailedEventAndNoReturnedEvent() {
        // Guards a reversal that tells the wallet the money is back before the executor has reversed the transfer,
        // that sends no Failed event, which the row in TRANSFERRED never sent, or that sends two.
        Payout payout = waiting(PayoutStatus.TRANSFERRED).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));
        List<String> queued = queuedEvents(payout);

        OperatorWrite write = store.operatorReverse(1L, READ_VERSION, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.APPLIED);
        assertThat(snapshotOf(write).status()).isEqualTo(PayoutStatus.REVERSING);
        assertThat(snapshotOf(write).reasonCode()).isEqualTo(PayoutReason.PAYOUT_FAILED);
        assertThat(payout.getAttentionFrom()).isNull();
        assertThat(queued).containsExactly("Failed: REVERSING, PAYOUT_FAILED");
        verify(outbox).publishFailed(payout, ACTED_AT);
        verify(outbox, never()).publishReturned(any(), any());
        verifyNoMoreInteractions(outbox);
        verifyNoInteractions(accounts);
    }

    @Test
    void anOperatorReversalUnderAnOlderVersionChangesNothing() {
        // Guards a reversal decided on a Stripe check of a row that has since changed: the check may not have seen
        // the payout that now exists, and a reversal of money that reached a bank cannot be taken back.
        Payout payout = waiting(PayoutStatus.TRANSFERRED).version(MOVED_VERSION).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));

        OperatorWrite write = store.operatorReverse(1L, READ_VERSION, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.CHANGED);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.NEEDS_ATTENTION);
        assertThat(payout.getAttentionFrom()).isEqualTo(PayoutStatus.TRANSFERRED);
        assertThat(payout.getReasonCode()).isNull();
        verifyNoInteractions(outbox, accounts);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PayoutStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "NEEDS_ATTENTION")
    void anOperatorReversalOfAPayoutThatLeftNeedsAttentionChangesNothing(PayoutStatus status) {
        // Guards a reversal of a row that is already in another step, such as one whose payout the webhook adopted
        // while Stripe was asked, which would reverse money on its way to a bank.
        Payout payout = row(status).version(MOVED_VERSION).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));

        OperatorWrite write = store.operatorReverse(1L, READ_VERSION, ACTED_AT);

        assertThat(write.kind()).isEqualTo(OperatorWrite.Kind.NOT_WAITING);
        assertThat(payout.getStatus()).isEqualTo(status);
        assertThat(payout.getClaimedUntil()).isEqualTo(TOKEN);
        verifyNoInteractions(outbox, accounts);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"operatorReturn", "operatorReverse"})
    void anOperatorWriteOnAnUnknownRowFindsNothing(String write) {
        // Guards a write on a row that was deleted or never existed answering as if it had moved something.
        OperatorWrite written = operatorWrite(write, READ_VERSION);

        assertThat(written.kind()).isEqualTo(OperatorWrite.Kind.NOT_FOUND);
        assertThat(written.payout()).isNull();
        verify(payouts).lockById(1L);
        verifyNoInteractions(outbox, accounts);
    }

    @ParameterizedTest(name = "{0} from {1}")
    @CsvSource({"operatorReturn, TRANSFERRED", "operatorReverse, ACCEPTED", "operatorReverse, REVERSING"})
    void aWriteTheRowRefusesUnderTheVersionReadIsADefectThatWritesNoEvent(String write, PayoutStatus from) {
        // Guards the second layer under the operations class's own checks: a return of a transfer that may still be
        // paid out, or a reversal of a row with no transfer to reverse, fails and rolls back instead of crediting
        // the wallet or telling it a payout failed.
        Payout payout = waiting(from).build();
        when(payouts.lockById(1L)).thenReturn(Optional.of(payout));

        assertThatThrownBy(() -> operatorWrite(write, READ_VERSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REF);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.NEEDS_ATTENTION);
        assertThat(payout.getAttentionFrom()).isEqualTo(from);
        verifyNoInteractions(outbox, accounts);
    }

    @Test
    void theOperatorsWritesEachRunInOneTransactionAndItsReadsOnlyRead() throws NoSuchMethodException {
        // Guards a dropped @Transactional: the row's pessimistic lock is refused outside a transaction, and a move
        // could commit apart from its event. A read that opens as a write holds a connection it has no use for.
        List<Transactional> writes = List.of(
                transactionOf("requeue", String.class, Instant.class),
                transactionOf("operatorReturn", Long.class, Long.class, Instant.class),
                transactionOf("operatorReverse", Long.class, Long.class, Instant.class)
        );
        List<Transactional> reads = List.of(
                transactionOf("findForOperator", String.class),
                transactionOf("findAwaitingOperator", int.class),
                transactionOf("countAwaitingOperator", PayoutStatus.class)
        );

        assertThat(writes).doesNotContainNull().noneMatch(Transactional::readOnly);
        assertThat(reads).doesNotContainNull().allMatch(Transactional::readOnly);
    }

    private static Transactional transactionOf(String method, Class<?>... parameters) throws NoSuchMethodException {
        return PayoutStore.class.getMethod(method, parameters).getAnnotation(Transactional.class);
    }

    @Test
    void theOperatorSnapshotCarriesNoUserId() {
        // Guards the user id, which spends the user's balance, reaching a log line or an answer through the
        // snapshot the operator's code reads, logs and answers with.
        assertThat(Arrays.stream(PayoutSnapshot.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("user"));
        assertThat(PayoutSnapshot.of(waiting(PayoutStatus.TRANSFERRED).build()).toString()).doesNotContain(USER);
    }

    @Test
    void aSnapshotCopiesEachFieldOfTheRowUnderItsOwnName() {
        // Guards a field swapped or dropped when the row is copied, such as the transfer id shown as the payout id,
        // which an operator would act on. Every field holds a value of its own.
        Instant created = NOW.minusSeconds(7200);
        Instant transferred = NOW.minusSeconds(3600);
        Instant updated = NOW.plusSeconds(60);
        Payout payout = Payout.builder()
                .id(11L)
                .version(READ_VERSION)
                .transactionReference(REF)
                .userId(USER)
                .amount(new BigDecimal("75.0000"))
                .currency("PLN")
                .status(PayoutStatus.NEEDS_ATTENTION)
                .attentionFrom(PayoutStatus.TRANSFERRED)
                .attentionCause(AttentionCause.MULTIPLE)
                .attentionDetail("two live payouts")
                .reasonCode(PayoutReason.PAYOUT_FAILED)
                .failureCode("account_closed")
                .keyGen(2)
                .attempts(12)
                .nextAttemptAt(NOW)
                .stripeAccountId("acct_1")
                .stripeTransferId("tr_1")
                .stripePayoutId("po_1")
                .stripeReversalId("trr_1")
                .transferredAt(transferred)
                .paidAt(PAID_AT)
                .createdAt(created)
                .updatedAt(updated)
                .build();

        assertThat(PayoutSnapshot.of(payout)).isEqualTo(new PayoutSnapshot(
                11L,
                READ_VERSION,
                REF,
                PayoutStatus.NEEDS_ATTENTION,
                PayoutStatus.TRANSFERRED,
                AttentionCause.MULTIPLE,
                "two live payouts",
                new BigDecimal("75.0000"),
                "PLN",
                PayoutReason.PAYOUT_FAILED,
                "account_closed",
                2,
                12,
                NOW,
                "acct_1",
                "tr_1",
                "po_1",
                "trr_1",
                transferred,
                PAID_AT,
                created,
                updated
        ));
    }

    @Test
    void anOperatorFindsAPayoutInAnyStatusByItsReference() {
        // Guards a read that finds only waiting rows, which could not tell an operator what became of a payout a
        // repeated action found already moved.
        when(payouts.findByTransactionReference(REF)).thenReturn(Optional.of(row(PayoutStatus.PAID).build()));

        assertThat(store.findForOperator(REF)).hasValueSatisfying(found -> {
            assertThat(found.reference()).isEqualTo(REF);
            assertThat(found.status()).isEqualTo(PayoutStatus.PAID);
        });
        assertThat(store.findForOperator(OTHER_REF)).isEmpty();
    }

    @Test
    void theStoreKeepsTheRepositorysOrderAndAsksForAFirstPageOfTheLimit() {
        // Guards a store that reads past its limit, that skips the first page, or that reorders the repository's
        // longest-waiting-first answer, which would put the newest hand-over ahead of the one that has waited most.
        // The order itself is the query's, pinned by the test of its text below.
        Payout longest = waiting(PayoutStatus.REVERSING).id(1L).transactionReference(OTHER_REF).build();
        Payout newest = waiting(PayoutStatus.ACCEPTED).id(2L).build();
        when(payouts.findAwaitingOperator(PageRequest.of(0, 100))).thenReturn(List.of(longest, newest));

        List<PayoutSnapshot> listed = store.findAwaitingOperator(100);

        assertThat(listed).extracting(PayoutSnapshot::reference).containsExactly(OTHER_REF, REF);
        assertThat(listed)
                .extracting(PayoutSnapshot::attentionFrom)
                .containsExactly(PayoutStatus.REVERSING, PayoutStatus.ACCEPTED);
        verify(payouts).findAwaitingOperator(PageRequest.of(0, 100));
    }

    @Test
    void theCountOfPayoutsAwaitingAnOperatorAsksForTheStepGiven() {
        // Guards a count taken over every step or for another one, which would show the wrong series in the gauge
        // an operator is paged by.
        when(payouts.countAwaitingOperator(PayoutStatus.ACCEPTED)).thenReturn(1L);
        when(payouts.countAwaitingOperator(PayoutStatus.TRANSFERRED)).thenReturn(2L);
        when(payouts.countAwaitingOperator(PayoutStatus.REVERSING)).thenReturn(3L);

        assertThat(store.countAwaitingOperator(PayoutStatus.ACCEPTED)).isEqualTo(1L);
        assertThat(store.countAwaitingOperator(PayoutStatus.TRANSFERRED)).isEqualTo(2L);
        assertThat(store.countAwaitingOperator(PayoutStatus.REVERSING)).isEqualTo(3L);
    }

    @Test
    void theWaitingPayoutQueriesWriteTheStatusIntoThemAndListTheLongestWaitingFirst() throws NoSuchMethodException {
        // Guards a bound status parameter, which hides the predicate from the partial index idx_payouts_attention
        // in a generic plan, and a listing that is not ordered by the hand-over and then the id.
        String waitingStatus = "p.status = " + PayoutStatus.class.getName() + ".NEEDS_ATTENTION";
        String list = queryOf("findAwaitingOperator", Pageable.class);
        String count = queryOf("countAwaitingOperator", PayoutStatus.class);

        assertThat(list).contains(waitingStatus).contains("order by p.updatedAt, p.id");
        assertThat(count).contains(waitingStatus).contains("p.attentionFrom = :from");
    }

    /**
     * The query's text with every run of whitespace, line breaks of a text block included, as one space, so a
     * reflowed query still matches what a test looks for.
     */
    private static String queryOf(String method, Class<?>... parameters) throws NoSuchMethodException {
        return PayoutRepository.class.getMethod(method, parameters)
                .getAnnotation(Query.class)
                .value()
                .replaceAll("\\s+", " ")
                .trim();
    }
}
