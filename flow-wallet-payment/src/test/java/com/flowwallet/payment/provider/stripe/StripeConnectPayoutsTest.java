package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.PayoutRefusal;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.dto.ProviderTransfer;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import com.stripe.model.TransferReversal;
import com.stripe.model.TransferReversalCollection;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.PayoutListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.stripe.param.TransferReversalCollectionCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeConnectPayoutsTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String ACCOUNT = "acct_unit_test";

    private final StripeClient stripeClient = mock(StripeClient.class);
    private final StripeConnectPayouts payouts = new StripeConnectPayouts(stripeClient);

    private static Transfer transfer(long amount, long reversed) {
        var transfer = new Transfer();
        transfer.setId("tr_1");
        transfer.setDestination(ACCOUNT);
        transfer.setAmount(amount);
        transfer.setAmountReversed(reversed);
        transfer.setCurrency("pln");
        return transfer;
    }

    private static Payout payout(String id, String status, Map<String, String> metadata) {
        var payout = new Payout();
        payout.setId(id);
        payout.setStatus(status);
        payout.setMetadata(metadata);
        payout.setAmount(7500L);
        payout.setCurrency("pln");
        return payout;
    }

    /**
     * A Stripe failure of the given kind: {@code invalid} (400 or 404), {@code card} (402), {@code idempotency},
     * {@code api} (409 or 5xx), {@code rate} (429), {@code permission} (403) or {@code connection} (no answer).
     */
    private static StripeException failure(String kind, Integer status, String code) {
        return switch (kind) {
            case "invalid" -> new InvalidRequestException("refused", "amount", "req_1", code, status, null);
            case "card" -> new CardException("refused", "req_1", code, null, null, null, status, null);
            case "idempotency" -> new IdempotencyException("other parameters", "req_1", code, status);
            case "api" -> new ApiException("server", "req_1", code, status, null);
            case "rate" -> new RateLimitException("slow down", null, "req_1", code, status, null);
            case "permission" -> new PermissionException("not allowed", "req_1", code, status);
            case "connection" -> new ApiConnectionException("Read timed out");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @Test
    void aTransferGoesToTheAccountInMinorUnitsGroupedAndLabelledByTheReferenceAlone() throws Exception {
        // Guards a transfer the lookup could not find again (no group), one Stripe would count in the wrong unit, a
        // retry that a key without the generation would answer with a stored refusal, and a user id sent to Stripe.
        when(stripeClient.createTransfer(any(), anyString())).thenReturn(transfer(7500, 0));

        PayoutCallResult<ProviderTransfer> result =
                payouts.createTransfer(REFERENCE, ACCOUNT, new BigDecimal("75.0000"), "PLN", 2);

        ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
        verify(stripeClient).createTransfer(params.capture(), eq("wd-transfer-" + REFERENCE + "-2"));
        assertThat(params.getValue().toMap())
                .containsEntry("amount", 7500L)
                .containsEntry("currency", "pln")
                .containsEntry("destination", ACCOUNT)
                .containsEntry("transfer_group", "wd-" + REFERENCE)
                .containsEntry("metadata", Map.of(StripeConstants.META_WITHDRAWAL_REF, REFERENCE));
        assertThat(result).isEqualTo(new PayoutCallResult.Done<>(new ProviderTransfer(
                "tr_1",
                ACCOUNT,
                new BigDecimal("75.00"),
                new BigDecimal("0.00"),
                "PLN"
        )));
    }

    @ParameterizedTest(name = "{1} {0}")
    @CsvSource({
            "PLN, 75.00,  7500",
            "JPY, 1000,   1000",
            "HUF, 500,    50000",
    })
    void anAmountIsSentInTheUnitStripeCountsIn(String currency, String amount, long minor) throws Exception {
        // Guards a payout of a hundredth of the withdrawal, or a hundred times it, in a currency whose unit differs.
        when(stripeClient.createPayout(any(), anyString(), anyString())).thenReturn(payout("po_1", "pending", null));

        payouts.createPayout(ACCOUNT, REFERENCE, new BigDecimal(amount), currency, 0);

        ArgumentCaptor<PayoutCreateParams> params = ArgumentCaptor.forClass(PayoutCreateParams.class);
        verify(stripeClient).createPayout(params.capture(), anyString(), eq(ACCOUNT));
        assertThat(params.getValue().getAmount()).isEqualTo(minor);
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(nullValues = "NULL", value = {
            "invalid,     400,  balance_insufficient,   REFUSED_INSUFFICIENT_FUNDS",
            "invalid,     400,  transfers_not_allowed,  REFUSED_OTHER",
            "invalid,     400,  amount_too_small,       REFUSED_OTHER",
            "card,        402,  balance_insufficient,   REFUSED_INSUFFICIENT_FUNDS",
            "card,        400,  balance_insufficient,   UNKNOWN",
            "invalid,     402,  balance_insufficient,   UNKNOWN",
            "invalid,     400,  parameter_unknown,      UNKNOWN",
            "invalid,     400,  NULL,                   UNKNOWN",
            "invalid,     404,  balance_insufficient,   UNKNOWN",
            "api,         409,  idempotency_key_in_use, UNKNOWN",
            "api,         500,  NULL,                   UNKNOWN",
            "rate,        429,  rate_limit,             UNKNOWN",
            "permission,  403,  NULL,                   UNKNOWN",
            "connection,  NULL, NULL,                   UNKNOWN",
            "idempotency, 400,  idempotency_error,      DEFECT",
    })
    void onlyAListedCodeOnA400Or402MakesATransferRefusalDefinite(
            String kind,
            Integer status,
            String code,
            String expected
    ) throws Exception {
        // Guards a 409 from a concurrent attempt, or any answer that leaves the transfer's existence open, being read
        // as a refusal: the payout would be returned while its transfer lands in the connected account.
        when(stripeClient.createTransfer(any(), anyString())).thenThrow(failure(kind, status, code));

        PayoutCallResult<ProviderTransfer> result =
                payouts.createTransfer(REFERENCE, ACCOUNT, new BigDecimal("75.00"), "PLN", 0);

        switch (expected) {
            case "REFUSED_INSUFFICIENT_FUNDS" -> assertThat(result)
                    .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.INSUFFICIENT_FUNDS, code));
            case "REFUSED_OTHER" -> assertThat(result)
                    .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, code));
            case "UNKNOWN" -> assertThat(result).isInstanceOf(PayoutCallResult.Unknown.class);
            case "DEFECT" -> assertThat(result).isInstanceOf(PayoutCallResult.Defect.class);
            default -> throw new IllegalArgumentException(expected);
        }
    }

    @Test
    void aPayoutIsAStandardOneOnTheConnectedAccountLabelledByTheReference() throws Exception {
        // Guards a payout made on the platform's own balance, an instant one Stripe charges for, and one the lookup
        // could not match to its withdrawal.
        when(stripeClient.createPayout(any(), anyString(), anyString())).thenReturn(payout(
                "po_1",
                "pending",
                Map.of(StripeConstants.META_WITHDRAWAL_REF, REFERENCE)
        ));

        PayoutCallResult<ProviderPayout> result =
                payouts.createPayout(ACCOUNT, REFERENCE, new BigDecimal("75.00"), "PLN", 1);

        ArgumentCaptor<PayoutCreateParams> params = ArgumentCaptor.forClass(PayoutCreateParams.class);
        verify(stripeClient).createPayout(params.capture(), eq("wd-payout-" + REFERENCE + "-1"), eq(ACCOUNT));
        assertThat(params.getValue().toMap())
                .containsEntry("currency", "pln")
                .containsEntry("method", "standard")
                .containsEntry("metadata", Map.of(StripeConstants.META_WITHDRAWAL_REF, REFERENCE));
        assertThat(result).isEqualTo(new PayoutCallResult.Done<>(new ProviderPayout(
                "po_1",
                ProviderPayoutStatus.PENDING,
                null,
                REFERENCE,
                new BigDecimal("75.00"),
                "PLN"
        )));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "bank_account_unusable",
            "bank_account_declined",
            "bank_account_restricted",
            "bank_account_bad_routing_numbers",
            "account_closed",
            "no_account",
            "account_number_invalid",
            "payouts_not_allowed",
    })
    void aPayoutRefusedForItsBankAccountSaysSo(String code) throws Exception {
        // Guards a refused bank account being retried like missing funds, which would wait days for a payout the
        // bank account can never take instead of returning the money.
        when(stripeClient.createPayout(any(), anyString(), anyString())).thenThrow(failure("invalid", 400, code));

        assertThat(payouts.createPayout(ACCOUNT, REFERENCE, new BigDecimal("75.00"), "PLN", 0))
                .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.BANK_ACCOUNT, code));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"account_invalid", "amount_too_small", "amount_too_large"})
    void anyOtherListedPayoutRefusalIsDefiniteWithoutBlamingTheBankAccount(String code) throws Exception {
        // Guards a refusal that names neither funds nor the bank account setting the user's bank account back to
        // NOT_SET, or being left unknown and retried under a key Stripe answers with the stored refusal.
        when(stripeClient.createPayout(any(), anyString(), anyString())).thenThrow(failure("invalid", 400, code));

        assertThat(payouts.createPayout(ACCOUNT, REFERENCE, new BigDecimal("75.00"), "PLN", 0))
                .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, code));
    }

    @Test
    void aPayoutRefusedAtTheDailyLimitIsALimitRefusal() throws Exception {
        // Guards a limit Stripe resets the next day being left unknown: the retry would keep its key and get the
        // stored refusal back until the row needed an operator.
        when(stripeClient.createPayout(any(), anyString(), anyString()))
                .thenThrow(failure("invalid", 400, "payouts_limit_exceeded"));

        assertThat(payouts.createPayout(ACCOUNT, REFERENCE, new BigDecimal("75.00"), "PLN", 0))
                .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.LIMIT_REACHED, "payouts_limit_exceeded"));
    }

    @Test
    void aPayoutRefusedForTheAccountsFundsIsAFundsRefusal() throws Exception {
        // Guards money the transfer has not made available yet being treated as a bank account to replace.
        when(stripeClient.createPayout(any(), anyString(), anyString()))
                .thenThrow(failure("invalid", 400, "balance_insufficient"));

        assertThat(payouts.createPayout(ACCOUNT, REFERENCE, new BigDecimal("75.00"), "PLN", 0))
                .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.INSUFFICIENT_FUNDS, "balance_insufficient"));
    }

    @Test
    void theLookupFindsOnlyTheConnectedAccountsPayoutsForTheReferenceSinceTheGivenTime() throws Exception {
        // Guards adopting another withdrawal's payout, or one made by hand without metadata, and a lookup that
        // misses a payout created just before the transfer was recorded.
        when(stripeClient.listPayouts(any(), anyString())).thenReturn(List.of(
                payout("po_mine", "in_transit", Map.of(StripeConstants.META_WITHDRAWAL_REF, REFERENCE)),
                payout("po_other", "paid", Map.of(StripeConstants.META_WITHDRAWAL_REF, "other-reference")),
                payout("po_manual", "paid", null)
        ));
        Instant since = Instant.parse("2026-10-10T08:00:00Z");

        List<ProviderPayout> found = payouts.findPayouts(ACCOUNT, REFERENCE, since);

        ArgumentCaptor<PayoutListParams> params = ArgumentCaptor.forClass(PayoutListParams.class);
        verify(stripeClient).listPayouts(params.capture(), eq(ACCOUNT));
        assertThat(params.getValue().toMap()).containsEntry("created", Map.of("gte", since.getEpochSecond()));
        assertThat(found).extracting(ProviderPayout::id).containsExactly("po_mine");
        assertThat(found.getFirst().status()).isEqualTo(ProviderPayoutStatus.IN_TRANSIT);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "pending,    PENDING,      true",
            "in_transit, IN_TRANSIT,   true",
            "paid,       PAID,         true",
            "failed,     FAILED,       false",
            "canceled,   CANCELED,     false",
            "held,       UNRECOGNIZED, true",
            "NULL,       UNRECOGNIZED, true",
    })
    void aPayoutsStatusSaysWhetherItsMoneyMayStillReachTheBank(
            String stripeStatus,
            ProviderPayoutStatus status,
            boolean mayReachTheBank
    ) throws Exception {
        // Guards reversing a transfer whose payout may still be paid, which would pay both the bank and the wallet:
        // a status this service does not know counts as live.
        when(stripeClient.retrievePayout("po_1", ACCOUNT)).thenReturn(payout("po_1", stripeStatus, null));

        ProviderPayout payout = payouts.retrievePayout(ACCOUNT, "po_1");

        assertThat(payout.status()).isEqualTo(status);
        assertThat(payout.status().mayReachTheBank()).isEqualTo(mayReachTheBank);
    }

    @Test
    void theTransferLookupAsksForTheReferencesGroup() throws Exception {
        // Guards a lookup that finds no transfer because it asks for another group than the create sent, so a
        // transfer made before a crash would be made a second time.
        when(stripeClient.listTransfers(any())).thenReturn(List.of(transfer(7500, 0)));

        List<ProviderTransfer> found = payouts.findTransfers(REFERENCE);

        ArgumentCaptor<TransferListParams> params = ArgumentCaptor.forClass(TransferListParams.class);
        verify(stripeClient).listTransfers(params.capture());
        assertThat(params.getValue().getTransferGroup()).isEqualTo("wd-" + REFERENCE);
        assertThat(found).extracting(ProviderTransfer::id).containsExactly("tr_1");
    }

    @Test
    void aFailedReadIsALookupFailureNotAnEmptyAnswer() throws Exception {
        // Guards an outage read as "no transfer exists", which would let the caller return a payout whose transfer
        // Stripe holds.
        when(stripeClient.listTransfers(any())).thenThrow(failure("api", 500, null));

        assertThatThrownBy(() -> payouts.findTransfers(REFERENCE)).isInstanceOf(PayoutLookupException.class);
    }

    @Test
    void aReversalBringsBackOnlyThePartNotYetReversed() throws Exception {
        // Guards a reversal of the whole amount after a partial one, which Stripe refuses, and a retry that changes
        // the amount under the same key.
        Transfer transfer = transfer(7500, 2500);
        when(stripeClient.retrieveTransfer("tr_1")).thenReturn(transfer);
        var reversal = new TransferReversal();
        reversal.setId("trr_1");
        when(stripeClient.createReversal(eq(transfer), any(), anyString())).thenReturn(reversal);

        PayoutCallResult<String> result = payouts.reverseTransfer("tr_1", REFERENCE, new BigDecimal("75.00"), "PLN", 3);

        ArgumentCaptor<TransferReversalCollectionCreateParams> params =
                ArgumentCaptor.forClass(TransferReversalCollectionCreateParams.class);
        verify(stripeClient).createReversal(eq(transfer), params.capture(), eq("wd-reversal-" + REFERENCE + "-3"));
        assertThat(params.getValue().toMap())
                .containsEntry("amount", 5000L)
                .containsEntry("metadata", Map.of(StripeConstants.META_WITHDRAWAL_REF, REFERENCE));
        assertThat(result).isEqualTo(new PayoutCallResult.Done<>("trr_1"));
    }

    @Test
    void aTransferAlreadyReversedReportsItsReversalWithoutANewOne() throws Exception {
        // Guards a second reversal after an earlier attempt succeeded unrecorded: Stripe would refuse it, and the
        // payout would wait for a reversal that already happened.
        Transfer transfer = transfer(7500, 7500);
        var reversal = new TransferReversal();
        reversal.setId("trr_earlier");
        var reversals = new TransferReversalCollection();
        reversals.setData(List.of(reversal));
        transfer.setReversals(reversals);
        when(stripeClient.retrieveTransfer("tr_1")).thenReturn(transfer);

        PayoutCallResult<String> result = payouts.reverseTransfer("tr_1", REFERENCE, new BigDecimal("75.00"), "PLN", 0);

        assertThat(result).isEqualTo(new PayoutCallResult.Done<>("trr_earlier"));
        verify(stripeClient, never()).createReversal(any(), any(), anyString());
    }

    @Test
    void aTransferReversedWithoutAListedReversalIsUnknownRatherThanDone() throws Exception {
        // Guards a payout recorded as returned without a reversal id, which the RETURNED row needs, and a second
        // reversal of money Stripe already brought back.
        when(stripeClient.retrieveTransfer("tr_1")).thenReturn(transfer(7500, 7500));

        PayoutCallResult<String> result = payouts.reverseTransfer("tr_1", REFERENCE, new BigDecimal("75.00"), "PLN", 0);

        assertThat(result).isInstanceOf(PayoutCallResult.Unknown.class);
        verify(stripeClient, never()).createReversal(any(), any(), anyString());
    }

    @Test
    void anAlreadyReversedTransferReportsTheReversalLabelledWithTheReference() throws Exception {
        // Guards recording a reversal made by hand, which Stripe lists first, as this payout's own.
        Transfer transfer = transfer(7500, 7500);
        var manual = new TransferReversal();
        manual.setId("trr_manual");
        var ours = new TransferReversal();
        ours.setId("trr_ours");
        ours.setMetadata(Map.of(StripeConstants.META_WITHDRAWAL_REF, REFERENCE));
        var reversals = new TransferReversalCollection();
        reversals.setData(List.of(manual, ours));
        transfer.setReversals(reversals);
        when(stripeClient.retrieveTransfer("tr_1")).thenReturn(transfer);

        assertThat(payouts.reverseTransfer("tr_1", REFERENCE, new BigDecimal("75.00"), "PLN", 0))
                .isEqualTo(new PayoutCallResult.Done<>("trr_ours"));
    }

    @Test
    void aTransferThatCannotBeReadLeavesTheReversalUnknownAndUntried() throws Exception {
        // Guards a reversal created without knowing what was already reversed, and a read failure taken as a refusal.
        when(stripeClient.retrieveTransfer("tr_1")).thenThrow(failure("connection", null, null));

        PayoutCallResult<String> result = payouts.reverseTransfer("tr_1", REFERENCE, new BigDecimal("75.00"), "PLN", 0);

        assertThat(result).isInstanceOf(PayoutCallResult.Unknown.class);
        verify(stripeClient, never()).createReversal(any(), any(), anyString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"amount_too_small", "amount_too_large"})
    void anyOtherListedReversalRefusalIsDefinite(String code) throws Exception {
        // Guards a refused reversal left unknown, which would never move to a fresh key and would keep receiving the
        // stored refusal until the operator stepped in.
        Transfer transfer = transfer(7500, 0);
        when(stripeClient.retrieveTransfer("tr_1")).thenReturn(transfer);
        when(stripeClient.createReversal(eq(transfer), any(), anyString())).thenThrow(failure("invalid", 400, code));

        assertThat(payouts.reverseTransfer("tr_1", REFERENCE, new BigDecimal("75.00"), "PLN", 0))
                .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.OTHER, code));
    }

    @Test
    void aReversalRefusedForTheAccountsFundsIsAFundsRefusal() throws Exception {
        // Guards a reversal the connected account cannot cover yet being left unknown, which would never move to a
        // fresh key and would keep receiving the stored refusal.
        Transfer transfer = transfer(7500, 0);
        when(stripeClient.retrieveTransfer("tr_1")).thenReturn(transfer);
        when(stripeClient.createReversal(eq(transfer), any(), anyString()))
                .thenThrow(failure("invalid", 400, "balance_insufficient"));

        assertThat(payouts.reverseTransfer("tr_1", REFERENCE, new BigDecimal("75.00"), "PLN", 0))
                .isEqualTo(new PayoutCallResult.Refused<>(PayoutRefusal.INSUFFICIENT_FUNDS, "balance_insufficient"));
    }

    @Test
    void theAvailableBalanceIsTheCurrencysAvailableCardFundsInMajorUnits() throws Exception {
        // Guards counting pending funds, funds of a kind a transfer does not draw on, or another currency's balance
        // as cover for a payout, and a reserve read a hundred times too large.
        var cardFunds = new Balance.Available.SourceTypes();
        cardFunds.setCard(814824L);
        var pln = new Balance.Available();
        pln.setCurrency("pln");
        pln.setAmount(864824L);
        pln.setSourceTypes(cardFunds);
        var usd = new Balance.Available();
        usd.setCurrency("usd");
        usd.setAmount(5000L);
        var balance = new Balance();
        balance.setAvailable(List.of(pln, usd));
        when(stripeClient.retrieveBalance()).thenReturn(balance);

        assertThat(payouts.availableBalance("PLN")).isEqualByComparingTo("8148.24");
        assertThat(payouts.availableBalance("EUR")).isEqualByComparingTo("0");
    }
}
