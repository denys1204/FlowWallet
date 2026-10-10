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
            "UGX, 0, 2",
            "ISK, 0, 2",
            "KWD, 2, 3",
            "isk, 0, 2",
    })
    void eachCurrencyHasTheScaleAndExponentStripeDocuments(String currency, int acceptedScale, int exponent) {
        // Guards the two numbers drifting apart for the currencies where they differ. ISK is the case where
        // Stripe takes hundredths but charges whole krónur: an accepted scale of 2 would send 1050 for 10.50,
        // which Stripe refuses after the row is reserved. UGX follows the same rule: Stripe's page lists it as
        // zero-decimal, but its API refuses a UGX amount that is not a multiple of 100, so at exponent 0 a charge
        // would be a hundredth of the amount credited. MGA is zero-decimal at Stripe although ISO gives it two
        // decimals.
        assertThat(StripeCurrencyRules.of(currency)).isEqualTo(new CurrencyRule(acceptedScale, exponent));
    }

    @ParameterizedTest(name = "a {0} payout carries at most {1} decimals")
    @CsvSource({
            "PLN, 2",
            "HUF, 0",
            "TWD, 0",
            "huf, 0",
            "JPY, 0",
            "ISK, 0",
            "UGX, 0",
            "KWD, 2",
    })
    void aPayoutUsesItsOwnGridWhereStripeDiffersFromItsCharges(String currency, int payoutScale) {
        // Guards HUF and TWD payouts in hundredths, which Stripe refuses at the payout step after the transfer, and
        // the payout grid drifting from the charge grid for every other currency.
        assertThat(StripeCurrencyRules.payoutScale(currency)).isEqualTo(payoutScale);
    }
}
