package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Valid
    private final Account account = new Account();

    /**
     * The connected account every user's payouts go through, opened with one fake identity: Payment Service never
     * learns who the user is, and Stripe's test mode accepts this person for any number of accounts. The values are
     * Stripe's test values for a Polish individual (an address line that passes verification, a date of birth that
     * does), so the country and the rest change together. {@code tosIp} stands for the user's address, which the
     * services never see. Startup refuses a live Stripe key, so this identity never reaches live mode.
     */
    @Getter
    @Setter
    public static class Account {
        private static final String IPV4 = "((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)";

        @NotNull(message = "payment.payout.account.country is required")
        @Pattern(regexp = "[A-Z]{2}", message = "payment.payout.account.country must be two capital letters")
        private String country = "PL";

        @NotBlank(message = "payment.payout.account.first-name is required")
        private String firstName = "Jenny";

        @NotBlank(message = "payment.payout.account.last-name is required")
        private String lastName = "Rosen";

        @NotBlank(message = "payment.payout.account.email is required")
        private String email = "jenny.rosen@example.com";

        @NotNull(message = "payment.payout.account.date-of-birth is required")
        @Past(message = "payment.payout.account.date-of-birth must be in the past")
        private LocalDate dateOfBirth = LocalDate.of(1901, 1, 1);

        @NotBlank(message = "payment.payout.account.address-line1 is required")
        private String addressLine1 = "address_full_match";

        @NotBlank(message = "payment.payout.account.city is required")
        private String city = "Warszawa";

        @NotBlank(message = "payment.payout.account.postal-code is required")
        private String postalCode = "00-001";

        @NotBlank(message = "payment.payout.account.phone is required")
        private String phone = "+48500000000";

        /**
         * Stripe refuses an {@code example.com} business URL, so the profile names the product and its category
         * instead.
         */
        @NotBlank(message = "payment.payout.account.product-description is required")
        private String productDescription = "Test-mode withdrawals from a FlowWallet wallet";

        @NotNull(message = "payment.payout.account.merchant-category-code is required")
        @Pattern(regexp = "\\d{4}", message = "payment.payout.account.merchant-category-code must be four digits")
        private String merchantCategoryCode = "5734";

        @NotNull(message = "payment.payout.account.tos-ip is required")
        @Pattern(regexp = IPV4, message = "payment.payout.account.tos-ip must be an IPv4 address")
        private String tosIp = "127.0.0.1";
    }

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
