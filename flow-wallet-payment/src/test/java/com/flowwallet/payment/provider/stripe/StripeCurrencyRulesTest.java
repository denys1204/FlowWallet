package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.stripe.StripeCurrencyRules.CurrencyRule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StripeCurrencyRulesTest {
    @ParameterizedTest(name = "{0} accepts {1} decimals and is sent at exponent {2}")
    @CsvSource({
            "USD, 2, 2",
            "JPY, 0, 0",
            "MGA, 0, 0",
            "UGX, 0, 0",
            "ISK, 0, 2",
            "KWD, 2, 3",
            "isk, 0, 2",
    })
    void eachCurrencyHasTheScaleAndExponentStripeDocuments(String currency, int acceptedScale, int exponent) {
        // Guards the two numbers drifting apart for the currencies where they differ. ISK is the case where
        // Stripe takes hundredths but charges whole krónur: an accepted scale of 2 would send 1050 for 10.50,
        // which Stripe refuses after the row is reserved. MGA is zero-decimal at Stripe although ISO gives it two
        // decimals, and UGX stays zero-decimal although Stripe's page also describes it in hundredths.
        assertThat(StripeCurrencyRules.of(currency)).isEqualTo(new CurrencyRule(acceptedScale, exponent));
    }
}
