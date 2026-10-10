package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutPreflightRequest;
import com.flowwallet.payment.dto.PayoutPreflightResponse;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayoutPreflightTest {
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";

    private final PayoutAccountStore accounts = mock(PayoutAccountStore.class);
    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final PayoutProvider provider = mock(PayoutProvider.class);
    private final PayoutPreflight preflight =
            new PayoutPreflight(new PayoutTerms(new PayoutProperties()), accounts, payouts, provider);

    private static PayoutPreflightRequest request(String amount) {
        return new PayoutPreflightRequest("PLN", new BigDecimal(amount));
    }

    private void bankAccountReady() {
        PayoutAccount account = PayoutAccount.reserve(USER, "key-1", Instant.EPOCH);
        account.recordStripeAccount("acct_1");
        account.recordBankAccount("btok_1", "ba_1", "2874", "pln", BankAccountState.READY);
        when(accounts.findByUser(USER)).thenReturn(Optional.of(account));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "EUR, 10.00",
            "PLN, 4.99",
            "PLN, 0",
            "PLN, -10.00",
            "PLN, 10.001",
            "PLN, 1E+16",
            "PLN, 12345678E+2147483644",
    })
    void aWithdrawalOutsideTheTermsIsA400BeforeAnythingIsRead(String currency, String amount) {
        // Guards debiting a withdrawal Payment Service would return with TERMS_REFUSED, and an exponent that wraps an
        // int digit count and lets a huge amount through.
        assertThatThrownBy(() -> preflight.check(USER, new PayoutPreflightRequest(currency, new BigDecimal(amount))))
                .isInstanceOf(PayoutTermsRefusedException.class)
                .message()
                .doesNotContain(amount);

        verifyNoInteractions(accounts, payouts, provider);
    }

    @Test
    void aCallerWithoutAReadyBankAccountIsToldSoWithoutAskingStripe() {
        // Guards a Stripe call per withdrawal that would be refused for its bank account anyway.
        when(accounts.findByUser(USER)).thenReturn(Optional.empty());

        assertThat(preflight.check(USER, request("10.00"))).isEqualTo(new PayoutPreflightResponse("NOT_SET", null));

        verifyNoInteractions(provider, payouts);
    }

    @ParameterizedTest(name = "available {0}, owed {1}, amount {2} -> {3}")
    @CsvSource({
            "100.00, 0,     100.00, true",
            "100.00, 30.00, 70.00,  true",
            "100.00, 30.00, 70.01,  false",
            "0,      0,     5.00,   false",
    })
    void theReserveIsTheAvailableBalanceLessWhatUntransferredPayoutsOwe(
            String available,
            String owed,
            String amount,
            boolean sufficient
    ) {
        // Guards counting money already promised to accepted payouts as cover for a new one, which a burst of
        // withdrawals would turn into PLATFORM_FUNDS_SHORT returns after the debit.
        bankAccountReady();
        when(provider.availableBalance("PLN")).thenReturn(new BigDecimal(available));
        when(payouts.sumNotYetTransferred("PLN")).thenReturn(new BigDecimal(owed));

        assertThat(preflight.check(USER, request(amount))).isEqualTo(new PayoutPreflightResponse("READY", sufficient));
    }

    @Test
    void aBankAccountReadyInAnotherCurrencyIsNotReadyForThisOne() {
        // Guards paying a EUR withdrawal out to a PLN bank account, which Stripe would refuse after the transfer.
        var properties = new PayoutProperties();
        var euro = new PayoutProperties.PayoutCurrency();
        euro.setCode("EUR");
        euro.setMinAmount(new BigDecimal("1.00"));
        properties.getCurrencies().add(euro);
        var twoCurrencies = new PayoutPreflight(new PayoutTerms(properties), accounts, payouts, provider);
        bankAccountReady();

        assertThat(twoCurrencies.check(USER, new PayoutPreflightRequest("EUR", new BigDecimal("10.00"))))
                .isEqualTo(new PayoutPreflightResponse("NOT_SET", null));
        verifyNoInteractions(provider);
    }

    @Test
    void theOwedSumIsReadBeforeTheBalance() {
        // Guards a transfer landing between the two reads being counted on neither side, which would overstate the
        // reserve; read in this order it is counted on both and can only refuse more.
        bankAccountReady();
        when(payouts.sumNotYetTransferred("PLN")).thenReturn(BigDecimal.ZERO);
        when(provider.availableBalance("PLN")).thenReturn(new BigDecimal("100.00"));

        preflight.check(USER, request("10.00"));

        var order = inOrder(payouts, provider);
        order.verify(payouts).sumNotYetTransferred("PLN");
        order.verify(provider).availableBalance("PLN");
    }

    @Test
    void aBalanceStripeCannotReportIsA502() {
        // Guards a lookup failure read as an empty reserve, which would hold every withdrawal with a 503.
        bankAccountReady();
        when(provider.availableBalance(anyString())).thenThrow(new PayoutLookupException("timeout", null));

        assertThatThrownBy(() -> preflight.check(USER, request("10.00")))
                .isInstanceOf(PayoutReserveUnavailableException.class);
    }
}
