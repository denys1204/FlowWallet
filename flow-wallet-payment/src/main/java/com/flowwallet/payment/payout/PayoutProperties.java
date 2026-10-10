package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * The terms Payment Service sets for a payout. The currencies are an indexed list rather than a map, because Spring
 * lower-cases map keys bound from environment variables. 1.0 lists PLN only, the platform's settlement currency.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "payment.payout")
public class PayoutProperties {
    @NotEmpty(message = "payment.payout.currencies must list at least one currency")
    private List<@Valid PayoutCurrency> currencies = new ArrayList<>(List.of(
            new PayoutCurrency("PLN", new BigDecimal("5.00"))
    ));

    @Getter
    @Setter
    public static class PayoutCurrency {
        @NotNull(message = "payment.payout.currencies[n].code is required")
        @Pattern(regexp = "[A-Z]{3}", message = "payment.payout.currencies[n].code must be three capital letters")
        private String code;

        /**
         * Stripe documents 5 PLN as the smallest PLN payout; a lower minimum would be refused at the payout step,
         * after the transfer.
         */
        @NotNull(message = "payment.payout.currencies[n].min-amount is required")
        @DecimalMin(
                value = "0",
                inclusive = false,
                message = "payment.payout.currencies[n].min-amount must be positive"
        )
        private BigDecimal minAmount;

        public PayoutCurrency() {
        }

        PayoutCurrency(String code, BigDecimal minAmount) {
            this.code = code;
            this.minAmount = minAmount;
        }

        @AssertTrue(message = "payment.payout.currencies[n].code must be an ISO 4217 currency")
        public boolean isKnownCurrency() {
            if (code == null) {
                return true;
            }
            try {
                return Currency.getInstance(code.toUpperCase(Locale.ROOT)) != null;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @AssertTrue(message = "payment.payout.currencies[n].min-amount must sit on the currency's payout grid")
        public boolean isMinimumOnTheGrid() {
            return code == null || minAmount == null || !isKnownCurrency()
                    || minAmount.stripTrailingZeros().scale() <= StripeCurrencyRules.payoutScale(code);
        }
    }
}
