package com.flowwallet.payment.provider.stripe.mapper;

import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.InvalidPaymentRequestException;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * This class decides how much money actually leaves a customer's card, and until now it had no direct
 * coverage at all.
 */
class StripeRequestMapperTest {
    private final StripeRequestMapper mapper = new StripeRequestMapper();

    private static PaymentRequestContext context(String amount, String currency) {
        return new PaymentRequestContext("ref-1", new BigDecimal(amount), currency, "user-1");
    }

    private long amountSentToStripe(String amount, String currency) {
        return mapper.toPaymentIntentParams(context(amount, currency)).getAmount();
    }

    @ParameterizedTest(name = "{1} {0} is charged as {2}")
    @CsvSource({
            // two-decimal, the ordinary case
            "50.00,  USD,   5000",
            "50.00,  EUR,   5000",
            // zero-decimal: the major unit is already the unit Stripe charges in
            "5000,   JPY,   5000",
            "5000,   KRW,   5000",
            // three-decimal: thousandths, not hundredths. Sending 5000 here would charge 5 KWD for 50.
            "50.00,  KWD,  50000",
            "10.00,  BHD,  10000",
            // MGA and ISK are the two currencies where the ISO exponent and Stripe's disagree, in opposite
            // directions. They are here to fail loudly if anyone rewrites this to read the exponent from
            // java.util.Currency: that would charge MGA a hundred times over and ISK a hundredth.
            "5000,   MGA,   5000",
            "5000,   ISK, 500000",
    })
    void convertsToTheUnitStripeCharges(String amount, String currency, long expected) {
        assertThat(amountSentToStripe(amount, currency)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{1} {0} is refused")
    @CsvSource({
            // finer than the currency accepts: rounding here would charge one figure and credit another
            "50.005, USD",
            "50.005, EUR",
            "5000.5, JPY",
            // three-decimal currencies are capped at two places, so every amount we accept converts to a
            // minor value ending in zero, which is what Stripe requires of them
            "50.001, KWD",
    })
    void refusesAmountsFinerThanTheCurrencyAccepts(String amount, String currency) {
        assertThatThrownBy(() -> amountSentToStripe(amount, currency))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessageContaining("decimal places");
    }

    @ParameterizedTest(name = "{1} {0} survives the round trip")
    @CsvSource({"50.00, USD", "0.01, EUR", "5000, JPY", "50.00, KWD", "5000, ISK", "5000, MGA"})
    void conversionLosesNothing(String amount, String currency) {
        long minor = amountSentToStripe(amount, currency);
        int exponent = StripeCurrencyRules.of(currency).transmitExponent();

        assertThat(BigDecimal.valueOf(minor).movePointLeft(exponent))
                .isEqualByComparingTo(new BigDecimal(amount));
    }

    @Test
    void trailingZerosAreNotMistakenForPrecision() {
        // 50.0000 is 50, and NUMERIC(19,4) hands amounts back at scale 4, so a scale check that did not
        // strip them would refuse every amount read back from the database.
        assertThat(amountSentToStripe("50.0000", "USD")).isEqualTo(5000L);
    }

    @Test
    void anUnknownCurrencyFallsBackToHundredthsRatherThanFailing() {
        // ISO validity is the HTTP layer's job. A code that reaches here unrecognised gets Stripe's default
        // exponent, and Stripe itself refuses the currency if it does not support it.
        assertThat(amountSentToStripe("50.00", "ZZZ")).isEqualTo(5000L);
    }

    @Test
    void metadataCarriesTheReferenceAndUser() {
        var params = mapper.toPaymentIntentParams(context("50.00", "USD"));

        assertThat(params.getMetadata())
                .containsEntry("transactionReference", "ref-1")
                .containsEntry("userId", "user-1");
        assertThat(params.getCurrency()).isEqualTo("usd");
    }
}
