package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.PayoutRefusal;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.dto.ProviderTransfer;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayoutStepsTest {
    private static final String REF = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final BigDecimal AMOUNT = new BigDecimal("75.0000");
    private static final Instant TRANSFERRED_AT = Instant.parse("2026-10-10T08:00:00Z");
    private static final int KEY_GEN = 2;

    private final PayoutProvider provider = mock(PayoutProvider.class);
    private final PayoutSteps steps = new PayoutSteps(provider, new PayoutProperties(), new StripeProperties());

    private static ClaimedPayout.Bank bank(boolean ready) {
        return new ClaimedPayout.Bank(7L, "acct_1", "ba_1", ready);
    }

    private static ClaimedPayout claim(PayoutStatus step, boolean earlier, ClaimedPayout.Bank bank, int attempts) {
        return claim(step, earlier, bank, attempts, Instant.now().plus(Duration.ofMinutes(2)));
    }

    private static ClaimedPayout claim(
            PayoutStatus step,
            boolean earlier,
            ClaimedPayout.Bank bank,
            int attempts,
            Instant claimedUntil
    ) {
        boolean transferred = step != PayoutStatus.ACCEPTED;
        return new ClaimedPayout(
                1L,
                REF,
                step,
                AMOUNT,
                "PLN",
                transferred ? "acct_1" : null,
                transferred ? "tr_1" : null,
                transferred ? TRANSFERRED_AT : null,
                KEY_GEN,
                attempts,
                earlier,
                claimedUntil,
                bank
        );
    }

    private static ProviderTransfer transfer(String destination, String amount, String reversed) {
        return new ProviderTransfer("tr_1", destination, new BigDecimal(amount), new BigDecimal(reversed), "PLN");
    }

    private static ProviderPayout payout(String id, ProviderPayoutStatus status, String amount) {
        return payoutTo(id, status, amount, "ba_1");
    }

    private static ProviderPayout payoutTo(String id, ProviderPayoutStatus status, String amount, String destination) {
        String failureCode = status == ProviderPayoutStatus.FAILED ? "no_account" : null;
        return new ProviderPayout(id, status, failureCode, REF, new BigDecimal(amount), "PLN", destination);
    }

    private static void assertSoon(Instant actual, Duration after) {
        assertThat(actual).isCloseTo(Instant.now().plus(after), within(Duration.ofSeconds(5)));
    }

    // Transfer step

    @Test
    void aFirstAttemptWithoutAReadyBankAccountReturnsWithoutCallingStripe() {
        // Guards a transfer to a connected account nobody can be paid out from, which would need a reversal to undo.
        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, false, bank(false), 0)))
                .isEqualTo(new StepOutcome.Returned(PayoutReason.BANK_ACCOUNT_NOT_SET, null));
        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, false, null, 0)))
                .isEqualTo(new StepOutcome.Returned(PayoutReason.BANK_ACCOUNT_NOT_SET, null));

        verifyNoInteractions(provider);
    }

    @Test
    void aFirstAttemptWithAReadyBankAccountCreatesTheTransferWithoutALookup() {
        // Guards a lookup on every transfer, a read the first try never needs, and a transfer under another key
        // generation or to another account than the payout account's.
        when(provider.createTransfer(REF, "acct_1", AMOUNT, "PLN", KEY_GEN))
                .thenReturn(new PayoutCallResult.Done<>(transfer("acct_1", "75.00", "0")));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, false, bank(true), 0)))
                .isEqualTo(new StepOutcome.Transferred("acct_1", "tr_1", false));
        verify(provider, never()).findTransfers(anyString());
    }

    @Test
    void aLaterAttemptAdoptsTheOneMatchingTransferItsGroupHolds() {
        // Guards a second transfer after a crash between Stripe's success and its record.
        when(provider.findTransfers(REF)).thenReturn(List.of(transfer("acct_1", "75.00", "0")));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.Transferred("acct_1", "tr_1", true));
        verify(provider, never()).createTransfer(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void aLaterAttemptThatFindsNothingCreatesUnderTheSameKeyGeneration() {
        // Guards a fresh key after an unknown outcome, which could create a second transfer while the first runs.
        when(provider.findTransfers(REF)).thenReturn(List.of());
        when(provider.createTransfer(REF, "acct_1", AMOUNT, "PLN", KEY_GEN))
                .thenReturn(new PayoutCallResult.Done<>(transfer("acct_1", "75.00", "0")));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.Transferred("acct_1", "tr_1", false));
    }

    @Test
    void aLaterAttemptCreatesTheTransferEvenWhenTheBankAccountIsNoLongerReady() {
        // Guards returning the money while a transfer Stripe may still be running lands afterwards: only the same key
        // settles it, and the payout step reverses it.
        when(provider.findTransfers(REF)).thenReturn(List.of());
        when(provider.createTransfer(REF, "acct_1", AMOUNT, "PLN", KEY_GEN))
                .thenReturn(new PayoutCallResult.Done<>(transfer("acct_1", "75.00", "0")));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, true, bank(false), 1)))
                .isEqualTo(new StepOutcome.Transferred("acct_1", "tr_1", false));
    }

    @Test
    void aLaterAttemptWithoutAConnectedAccountReturnsAfterTheLookup() {
        // Guards a transfer with no destination, and a return before the group lookup has shown nothing was sent.
        when(provider.findTransfers(REF)).thenReturn(List.of());

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, true, null, 1)))
                .isEqualTo(new StepOutcome.Returned(PayoutReason.BANK_ACCOUNT_NOT_SET, null));
        verify(provider).findTransfers(REF);
    }

    @Test
    void twoTransfersInTheGroupNeedAttention() {
        // Guards adopting one of two transfers for a reference, which would leave the other's money unaccounted for.
        when(provider.findTransfers(REF))
                .thenReturn(List.of(transfer("acct_1", "75.00", "0"), transfer("acct_1", "75.00", "0")));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, true, bank(true), 1)))
                .isInstanceOfSatisfying(StepOutcome.Attention.class, attention ->
                        assertThat(attention.cause()).isEqualTo(AttentionCause.MULTIPLE));
    }

    @ParameterizedTest(name = "to {0}, {1}, reversed {2}")
    @CsvSource({
            "acct_other, 75.00, 0",
            "acct_1,     70.00, 0",
            "acct_1,     75.00, 5.00",
    })
    void aFoundTransferWithOtherTermsNeedsAttention(String destination, String amount, String reversed) {
        // Guards adopting a transfer that went elsewhere, moved another sum or was partly reversed by hand.
        when(provider.findTransfers(REF)).thenReturn(List.of(transfer(destination, amount, reversed)));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, true, bank(true), 1)))
                .isInstanceOfSatisfying(StepOutcome.Attention.class, attention ->
                        assertThat(attention.cause()).isEqualTo(AttentionCause.MISMATCH));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "INSUFFICIENT_FUNDS, PLATFORM_FUNDS_SHORT",
            "OTHER,              TRANSFER_REFUSED",
            "BANK_ACCOUNT,       TRANSFER_REFUSED",
    })
    void aDefiniteTransferRefusalReturnsThePayout(PayoutRefusal refusal, PayoutReason reason) {
        // Guards a refused transfer left to retry, which would hold the money debited for nothing left the platform.
        when(provider.createTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(refusal, "some_code"));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, false, bank(true), 0)))
                .isEqualTo(new StepOutcome.Returned(reason, "some_code"));
    }

    @Test
    void anUnknownTransferBacksOffUnderTheSameKeyGeneration() {
        // Guards an unknown outcome taken as a refusal, which would return money a transfer may still move.
        when(provider.createTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Unknown<>("ApiConnectionException"));

        StepOutcome outcome = steps.work(claim(PayoutStatus.ACCEPTED, false, bank(true), 0));

        assertThat(outcome).isInstanceOfSatisfying(StepOutcome.RetryUnknown.class, retry ->
                assertSoon(retry.nextAttemptAt(), Duration.ofMinutes(1)));
    }

    @Test
    void theUnknownBackoffDoublesWithEachTry() {
        // Guards a fixed wait that would hit Stripe every minute through a long outage.
        when(provider.createTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Unknown<>("timeout"));

        StepOutcome outcome = steps.work(claim(PayoutStatus.ACCEPTED, false, bank(true), 3));

        assertThat(outcome).isInstanceOfSatisfying(StepOutcome.RetryUnknown.class, retry ->
                assertSoon(retry.nextAttemptAt(), Duration.ofMinutes(8)));
    }

    @Test
    void theUnknownAttemptLimitSendsTheStepToAttention() {
        // Guards an unknown outcome retried forever, with the money debited and no operator told.
        when(provider.createTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Unknown<>("timeout"));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, false, bank(true), 12)))
                .isEqualTo(new StepOutcome.Attention(AttentionCause.UNKNOWN_LIMIT, false, "timeout"));
    }

    @Test
    void aFailedGroupLookupBacksOffWithoutCreating() {
        // Guards an outage read as "no transfer exists", which would create under the key without knowing.
        when(provider.findTransfers(REF)).thenThrow(new PayoutLookupException("timeout", null));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, true, bank(true), 1)))
                .isInstanceOf(StepOutcome.RetryUnknown.class);
        verify(provider, never()).createTransfer(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void aDefectNeedsAttentionWithoutRaisingTheGeneration() {
        // Guards a fresh key after an idempotency error, which could create a second object.
        when(provider.createTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Defect<>("IdempotencyException"));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, false, bank(true), 0)))
                .isEqualTo(new StepOutcome.Attention(AttentionCause.DEFECT, false, "IdempotencyException"));
    }

    // Payout step

    @Test
    void aFirstAttemptWithAReadyBankAccountCreatesThePayoutWithoutAListing() {
        // Guards a listing on every payout, and a payout made on another account than the one the transfer used.
        when(provider.createPayout("acct_1", REF, AMOUNT, "PLN", KEY_GEN))
                .thenReturn(new PayoutCallResult.Done<>(payout("po_1", ProviderPayoutStatus.PENDING, "75.00")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 0)))
                .isEqualTo(new StepOutcome.PayoutCreated("po_1", false));
        verify(provider, never()).findPayouts(anyString(), anyString(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ProviderPayoutStatus.class, names = {"PENDING", "IN_TRANSIT", "PAID"})
    void aLaterAttemptAdoptsTheOneLivePayoutWithNoEvent(ProviderPayoutStatus status) {
        // Guards a second payout after a crash between Stripe's success and its record, which would pay twice.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_1", status, "75.00")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.PayoutCreated("po_1", true));
        verify(provider, never()).createPayout(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void onlyFailedPayoutsFoundAreAdoptedAndReversedAsPayoutFailed() {
        // Guards a failed payout left unrecorded, and a second payout to a bank account that just failed.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_failed", ProviderPayoutStatus.FAILED, "75.00")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.FailedPayoutAdopted("po_failed", "no_account", "ba_1"));
    }

    @Test
    void aFailedPayoutFoundByTheListingCarriesItsDestination() {
        // Guards a failed payout's bank account never being reset: the outcome must carry the account the payout was
        // sent to, taken from the payout and not from the claim's current bank account, so a replaced account is kept.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payoutTo("po_failed", ProviderPayoutStatus.FAILED, "75.00", "ba_old")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.FailedPayoutAdopted("po_failed", "no_account", "ba_old"));
    }

    @Test
    void aCanceledPayoutFoundByTheListingCarriesNoDestination() {
        // Guards a cancelation resetting a bank account that nothing is wrong with: a canceled payout says nothing
        // about the account, so only a failed one may hand its destination to the reset.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_canceled", ProviderPayoutStatus.CANCELED, "75.00")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.FailedPayoutAdopted("po_canceled", null, null));
    }

    @Test
    void aFailedPayoutWithOtherTermsNeedsAttentionInsteadOfBeingAdopted() {
        // Guards adopting, and resetting a bank account for, a failed payout that is not this withdrawal's.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_failed", ProviderPayoutStatus.FAILED, "10.00")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isInstanceOfSatisfying(StepOutcome.Attention.class, attention ->
                        assertThat(attention.cause()).isEqualTo(AttentionCause.MISMATCH));
    }

    @Test
    void twoLivePayoutsNeedAttention() {
        // Guards adopting one of two live payouts while the other also pays the bank.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of(
                payout("po_1", ProviderPayoutStatus.PENDING, "75.00"),
                payout("po_2", ProviderPayoutStatus.PAID, "75.00")
        ));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isInstanceOfSatisfying(StepOutcome.Attention.class, attention ->
                        assertThat(attention.cause()).isEqualTo(AttentionCause.MULTIPLE));
    }

    @Test
    void aFoundPayoutWithOtherTermsNeedsAttention() {
        // Guards adopting a payout of another sum as this withdrawal's.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_1", ProviderPayoutStatus.PENDING, "10.00")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isInstanceOfSatisfying(StepOutcome.Attention.class, attention ->
                        assertThat(attention.cause()).isEqualTo(AttentionCause.MISMATCH));
    }

    @Test
    void aBankAccountNoLongerReadyReversesOnlyAfterAListingFindsNoLivePayout() {
        // Guards reversing a transfer whose payout may already be on its way, which would pay both bank and wallet.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(false), 0)))
                .isEqualTo(new StepOutcome.ReversalStarted(PayoutReason.BANK_ACCOUNT_NOT_SET, null));
        verify(provider).findPayouts(anyString(), anyString(), any());
        verify(provider, never()).createPayout(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PayoutRefusal.class, names = {"INSUFFICIENT_FUNDS", "LIMIT_REACHED"})
    void aFundsOrLimitRefusalWaitsTheRetryIntervalUnderAFreshKey(PayoutRefusal refusal) {
        // Guards money the transfer has not made available yet, or a daily limit, returned at once instead of waited
        // for, and a retry under the old key, which Stripe answers with the stored refusal.
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(refusal, "balance_insufficient"));

        StepOutcome outcome = steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 0));

        assertThat(outcome).isInstanceOfSatisfying(StepOutcome.RetryRefused.class, retry ->
                assertSoon(retry.nextAttemptAt(), Duration.ofHours(2)));
    }

    @Test
    void aFundsRefusalOnTheLastTryReversesAsPayoutFailedAfterAListing() {
        // Guards a wait without end, and a reversal while an earlier try's payout may be live.
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.INSUFFICIENT_FUNDS, "balance_insufficient"));
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 12)))
                .isEqualTo(new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, "balance_insufficient"));
        verify(provider).findPayouts(anyString(), anyString(), any());
    }

    @Test
    void aBankRefusalReversesAsBankAccountRejectedWithTheBankAccountItSaw() {
        // Guards resetting a bank account the user replaced meanwhile: the record compares it with the one seen here.
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.BANK_ACCOUNT, "bank_account_unusable"));
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 0)))
                .isEqualTo(new StepOutcome.BankAccountRejected("bank_account_unusable", 7L, "ba_1"));
    }

    @Test
    void anOtherRefusalReversesAsPayoutFailed() {
        // Guards a refusal that never clears being retried for a day.
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, "amount_too_small"));
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 0)))
                .isEqualTo(new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, "amount_too_small"));
    }

    @Test
    void aLivePayoutListedAfterARefusalIsAdoptedInsteadOfReversed() {
        // Guards reversing a transfer whose payout an earlier, unrecorded try already made.
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, "amount_too_small"));
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_1", ProviderPayoutStatus.IN_TRANSIT, "75.00")));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 0)))
                .isEqualTo(new StepOutcome.PayoutCreated("po_1", true));
    }

    @Test
    void aRefusalAfterAnEarlierListingDoesNotListAgain() {
        // Guards a second listing in one try, a Stripe call that can decide nothing the first did not.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, "amount_too_small"));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, "amount_too_small"));
        verify(provider, times(1)).findPayouts(anyString(), anyString(), any());
    }

    @Test
    void aFailedListingNeverStartsAReversal() {
        // Guards a reversal started without knowing whether a payout is live.
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.BANK_ACCOUNT, "bank_account_unusable"));
        when(provider.findPayouts(anyString(), anyString(), any())).thenThrow(new PayoutLookupException("x", null));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 0)))
                .isInstanceOf(StepOutcome.RetryUnknown.class);
    }

    @Test
    void anUnknownPayoutKeepsTheGeneration() {
        // Guards a fresh key after a timeout, which could make a second payout while the first is created.
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Unknown<>("timeout"));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, false, bank(true), 0)))
                .isInstanceOf(StepOutcome.RetryUnknown.class);
    }

    @Test
    void theListingLooksBackAnHourBeforeTheTransfer() {
        // Guards missing a payout created just before the transfer was recorded.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Done<>(payout("po_1", ProviderPayoutStatus.PENDING, "75.00")));

        steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1));

        verify(provider).findPayouts("acct_1", REF, TRANSFERRED_AT.minus(Duration.ofHours(1)));
    }

    // Reversal step

    @Test
    void aLivePayoutNeedsAttentionBeforeAnyReversal() {
        // Guards reversing a transfer whose money a second payout is taking to the bank: both would be paid.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_2", ProviderPayoutStatus.PENDING, "75.00")));

        assertThat(steps.work(claim(PayoutStatus.REVERSING, false, bank(true), 0)))
                .isInstanceOfSatisfying(StepOutcome.Attention.class, attention ->
                        assertThat(attention.cause()).isEqualTo(AttentionCause.LIVE_PAYOUT));
        verify(provider, never()).reverseTransfer(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void aFailedPayoutDoesNotStopTheReversal() {
        // Guards a returned payout stuck behind the very failure that returned it.
        when(provider.findPayouts(anyString(), anyString(), any()))
                .thenReturn(List.of(payout("po_1", ProviderPayoutStatus.FAILED, "75.00")));
        when(provider.reverseTransfer("tr_1", REF, AMOUNT, "PLN", KEY_GEN))
                .thenReturn(new PayoutCallResult.Done<>("trr_1"));

        assertThat(steps.work(claim(PayoutStatus.REVERSING, false, bank(false), 0)))
                .isEqualTo(new StepOutcome.Reversed("trr_1"));
    }

    @Test
    void aRefusedReversalWaitsTheRetryIntervalUnderAFreshKey() {
        // Guards a reversal the connected account cannot cover yet, retried under the key Stripe answers with the
        // stored refusal.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        when(provider.reverseTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.INSUFFICIENT_FUNDS, "balance_insufficient"));

        StepOutcome outcome = steps.work(claim(PayoutStatus.REVERSING, false, bank(true), 0));

        assertThat(outcome).isInstanceOfSatisfying(StepOutcome.RetryRefused.class, retry ->
                assertSoon(retry.nextAttemptAt(), Duration.ofMinutes(30)));
    }

    @Test
    void aRefusedReversalOnTheLastTryNeedsAttentionWithTheGenerationRaised() {
        // Guards a requeue whose first try gets the stored refusal back instead of reaching Stripe.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        when(provider.reverseTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, "amount_too_large"));

        assertThat(steps.work(claim(PayoutStatus.REVERSING, false, bank(true), 12)))
                .isEqualTo(new StepOutcome.Attention(AttentionCause.REVERSAL_REFUSED, true, "amount_too_large"));
    }

    @Test
    void anUnknownReversalKeepsTheGeneration() {
        // Guards a fresh key after a timeout, which could reverse twice.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        when(provider.reverseTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Unknown<>("timeout"));

        assertThat(steps.work(claim(PayoutStatus.REVERSING, false, bank(true), 0)))
                .isInstanceOf(StepOutcome.RetryUnknown.class);
    }

    @Test
    void aFailedPayoutListingBacksOffWithoutReversing() {
        // Guards a reversal made without knowing whether a payout is live.
        when(provider.findPayouts(anyString(), anyString(), any())).thenThrow(new PayoutLookupException("x", null));

        assertThat(steps.work(claim(PayoutStatus.REVERSING, false, bank(true), 0)))
                .isInstanceOf(StepOutcome.RetryUnknown.class);
        verify(provider, never()).reverseTransfer(anyString(), anyString(), any(), anyString(), anyInt());
    }

    // All steps

    @Test
    void anUnexpectedExceptionIsAnUnknownOutcome() {
        // Guards a defect that loops forever: as an unknown outcome it reaches NEEDS_ATTENTION at the limit.
        when(provider.createTransfer(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("boom"));

        assertThat(steps.work(claim(PayoutStatus.ACCEPTED, false, bank(true), 0)))
                .isInstanceOf(StepOutcome.RetryUnknown.class);
    }

    @Test
    void noStripeCallStartsWithoutRoomOnTheLease() {
        // Guards a call that outlives the claim, so a second executor could act on the same step meanwhile.
        ClaimedPayout shortLease = claim(PayoutStatus.ACCEPTED, false, bank(true), 0, Instant.now().plusSeconds(5));

        assertThat(steps.work(shortLease)).isInstanceOf(StepOutcome.Released.class);
        verifyNoInteractions(provider);
    }

    @Test
    void aReversalNeedsRoomForTwoCalls() {
        // Guards a reversal that retrieves and creates on a lease with room for one call only.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        ClaimedPayout lease = claim(PayoutStatus.REVERSING, false, bank(true), 0, Instant.now().plusSeconds(22));

        assertThat(steps.work(lease)).isInstanceOf(StepOutcome.Released.class);
        verify(provider).findPayouts(anyString(), anyString(), any());
        verify(provider, never()).reverseTransfer(anyString(), anyString(), any(), anyString(), anyInt());
    }

    @Test
    void aBankRefusalOnALaterTryNamesNoBankAccountToReset() {
        // Guards resetting a bank account the user set after an unrecorded try: under the same key Stripe answers with
        // the refusal it stored for the old one.
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn(new PayoutCallResult.Refused<>(PayoutRefusal.BANK_ACCOUNT, "bank_account_unusable"));

        assertThat(steps.work(claim(PayoutStatus.TRANSFERRED, true, bank(true), 1)))
                .isEqualTo(new StepOutcome.BankAccountRejected("bank_account_unusable", 7L, null));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "transfer lookup",
            "transfer create",
            "payout listing",
            "payout create",
            "listing after a refusal",
            "reversal listing",
    })
    void everyStripeCallWaitsForRoomOnTheLease(String guard) {
        // Guards a call that outlives the claim at any of the step's guards, so a second executor could act on the
        // same step while it runs. One call needs its budget of 8s plus the 10s margin with the defaults; the refused
        // create takes two seconds, after which the lease no longer has room for the listing. The 1.5s of room before
        // the create absorbs a slow machine, and the create is verified so a slower one fails instead of skipping it.
        Instant oneCallLeft = Instant.now().plusMillis(19_500);
        Instant noCallLeft = Instant.now().plusSeconds(12);
        when(provider.findPayouts(anyString(), anyString(), any())).thenReturn(List.of());
        when(provider.findTransfers(anyString())).thenReturn(List.of());
        when(provider.createPayout(anyString(), anyString(), any(), anyString(), anyInt())).thenAnswer(call -> {
            Thread.sleep(2000);
            return new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, "amount_too_small");
        });
        ClaimedPayout claim = switch (guard) {
            case "transfer lookup" -> claim(PayoutStatus.ACCEPTED, true, bank(true), 1, noCallLeft);
            case "transfer create" -> claim(PayoutStatus.ACCEPTED, false, bank(true), 0, noCallLeft);
            case "payout listing" -> claim(PayoutStatus.TRANSFERRED, true, bank(true), 1, noCallLeft);
            case "payout create" -> claim(PayoutStatus.TRANSFERRED, false, bank(true), 0, noCallLeft);
            case "listing after a refusal" -> claim(PayoutStatus.TRANSFERRED, false, bank(true), 0, oneCallLeft);
            default -> claim(PayoutStatus.REVERSING, false, bank(true), 0, noCallLeft);
        };

        assertThat(steps.work(claim)).isInstanceOf(StepOutcome.Released.class);
        if (!guard.equals("listing after a refusal")) {
            verifyNoInteractions(provider);
        } else {
            verify(provider).createPayout(anyString(), anyString(), any(), anyString(), anyInt());
            verify(provider, never()).findPayouts(anyString(), anyString(), any());
        }
    }
}
