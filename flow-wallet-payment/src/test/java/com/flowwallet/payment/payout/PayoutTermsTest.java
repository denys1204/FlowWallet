package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutTermsTest {
    private final PayoutProperties properties = new PayoutProperties();
    private final PayoutTerms terms = new PayoutTerms(properties);

    @Test
    void aListedCurrencyFromItsMinimumOnItsGridIsAccepted() {
        // Guards refusing the payouts the terms allow, which would return every debited withdrawal.
        assertThat(terms.refusal("PLN", new BigDecimal("5.00"))).isEmpty();
        assertThat(terms.refusal("pln", new BigDecimal("75.5"))).isEmpty();
    }

    @Test
    void aCurrencyOffTheListIsRefused() {
        // Guards paying out a currency the platform does not settle in, which it has no available balance of.
        assertThat(terms.refusal("USD", new BigDecimal("75.00"))).isPresent();
    }

    @Test
    void anAmountBelowTheMinimumIsRefusedWithTheMinimumAndNotTheAmount() {
        // Guards a payout Stripe refuses as too small after the transfer has already left the platform, a refusal that
        // leaves the user guessing the minimum, and one that repeats the amount into a log line or a problem detail.
        assertThat(terms.refusal("pln", new BigDecimal("4.99")))
                .hasValue("The smallest withdrawal in PLN is 5.00");
    }

    @Test
    void anAmountOffThePayoutGridIsRefusedWithoutQuotingIt() {
        // Guards a HUF payout in hundredths, which Stripe refuses at the payout step, and a reason that repeats the
        // amount into a log line or a problem detail.
        properties.setCurrencies(List.of(new PayoutProperties.PayoutCurrency("HUF", new BigDecimal("1000"))));

        assertThat(terms.refusal("HUF", new BigDecimal("1500.50")))
                .hasValueSatisfying(reason -> assertThat(reason).doesNotContain("1500"));
        assertThat(terms.refusal("HUF", new BigDecimal("1500"))).isEmpty();
    }
}
