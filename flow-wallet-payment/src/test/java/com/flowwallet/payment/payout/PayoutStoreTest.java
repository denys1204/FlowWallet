package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayoutStoreTest {
    private static final String REF = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final Instant CLAIMED_AT = Instant.parse("2026-10-10T09:00:00.123456Z");
    private static final Instant TOKEN = Instant.parse("2026-10-10T09:02:00.123456Z");
    private static final Instant NOW = Instant.parse("2026-10-10T09:00:30Z");

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
                new StepOutcome.FailedPayoutAdopted("po_failed", "no_account")
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
}
