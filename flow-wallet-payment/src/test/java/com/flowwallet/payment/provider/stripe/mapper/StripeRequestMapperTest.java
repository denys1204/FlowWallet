package com.flowwallet.payment.provider.stripe.mapper;

import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.InvalidPaymentRequestException;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
            // Stripe takes ISK in hundredths but charges whole krónur: 1050 would ask for 10.50, which it refuses
            "10.50,  ISK",
    })
    void refusesAmountsFinerThanTheCurrencyAccepts(String amount, String currency) {
        assertThatThrownBy(() -> amountSentToStripe(amount, currency))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessageContaining("decimal places");
    }

    @ParameterizedTest(name = "{1} {0} survives the round trip")
    @CsvSource({"50.00, USD", "10.01, EUR", "5000, JPY", "50.00, KWD", "5000, ISK", "5000, MGA"})
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

    @ParameterizedTest(name = "{0} is refused as a currency Stripe does not charge")
    @ValueSource(strings = {"XAU", "XTS", "XXX", "VES", "ZZZ"})
    void aCurrencyStripeDoesNotChargeIsRefusedBeforeTheReservation(String currency) {
        // Guards sending Stripe a currency it refuses every time. The refusal would come after the row was
        // reserved, so the key would be spent on a payment that could never start. The JDK accepts XAU, XTS and
        // XXX as ISO codes, so @Iso4217Currency lets them through.
        assertThatThrownBy(() -> mapper.validate(context("50.00", currency)))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessage("Stripe does not charge in " + currency);
    }

    @ParameterizedTest(name = "{1} {0} is refused below Stripe's minimum of {2}")
    @CsvSource({
            "49,     JPY, 50",
            "174.99, HUF, 175.00",
            "14.99,  CZK, 15.00",
            "3.99,   HKD, 4.00",
    })
    void anAmountBelowStripesMinimumChargeIsRefusedNamingTheMinimum(String amount, String currency, String minimum) {
        // Guards the deposit range's 1.00 floor being taken as enough in every currency. Stripe refuses a 1 JPY
        // charge, and after the reservation that refusal would cost the caller the key. The detail names the
        // minimum so the caller can correct the amount.
        assertThatThrownBy(() -> mapper.validate(context(amount, currency)))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessage(
                        "Minimum deposit amount in %s is %s, the smallest charge Stripe accepts in it"
                                .formatted(currency, minimum)
                );
    }

    @ParameterizedTest(name = "{1} {0} is accepted")
    @CsvSource({"50, JPY", "175, HUF", "1.00, USD", "1, VND", "1.00, TWD"})
    void anAmountAtStripesMinimumOrInACurrencyWithoutOneIsAccepted(String amount, String currency) {
        // Guards an off-by-one at the minimum, and a currency Stripe lists no minimum for being refused locally.
        // Such a currency's minimum depends on the account's settlement currency, which only Stripe can judge.
        assertThatCode(() -> mapper.validate(context(amount, currency))).doesNotThrowAnyException();
    }

    @Test
    void metadataCarriesTheReferenceAndNoUserId() {
        // Guards the user id leaking to Stripe: it is a bearer credential (ADR 0003) that Stripe has no need of,
        // and the reference alone is enough to trace the payment back to this service's record of it (ADR 0027).
        var params = mapper.toPaymentIntentParams(context("50.00", "USD"));

        assertThat(params.getMetadata())
                .containsEntry("transactionReference", "ref-1")
                .doesNotContainKey("userId");
        assertThat(params.getCurrency()).isEqualTo("usd");
    }
}
