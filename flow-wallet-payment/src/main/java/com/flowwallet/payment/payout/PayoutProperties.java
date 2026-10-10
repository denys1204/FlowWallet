package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import com.flowwallet.payment.provider.stripe.StripePayoutMinimums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
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

    @Valid
    private final Executor executor = new Executor();

    /**
     * Checked here rather than on each currency, because Stripe's minimum depends on the connected account's country.
     * A currency Stripe documents no minimum for in that country is not checked.
     */
    @AssertTrue(message = "payment.payout.currencies[n].min-amount must not be below Stripe's minimum payout")
    public boolean isEveryMinimumAtLeastStripes() {
        if (account.getCountry() == null) {
            return true;
        }
        return currencies.stream()
                .filter(entry -> entry.getCode() != null && entry.getMinAmount() != null)
                .allMatch(entry -> StripePayoutMinimums.of(account.getCountry(), entry.getCode())
                        .map(stripeMinimum -> entry.getMinAmount().compareTo(stripeMinimum) >= 0)
                        .orElse(true));
    }

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

    /**
     * How {@code PayoutExecutor} works the Transfer, Payout and Reversal steps. Every limit counts the step's tries in
     * {@code attempts}, the last one included, and every move into a step starts the count again; the three limits
     * share that count, so their defaults are equal and one cause never cuts another's wait short. The lease must
     * cover a step's three Stripe calls, which {@code PayoutExecutor} checks at startup against the Stripe timeouts.
     * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
     */
    @Getter
    @Setter
    public static class Executor {
        /**
         * Whether the executor runs at all. Read by {@code @ConditionalOnProperty}, whose {@code matchIfMissing}
         * holds the same default.
         */
        private boolean enabled = true;

        /**
         * Pause between two runs, in ms. {@code @Scheduled} reads it through a placeholder with the same default.
         */
        @Min(value = 1000, message = "payment.payout.executor.interval-ms must be at least 1000")
        private long intervalMs = 10_000;

        @Min(value = 1, message = "payment.payout.executor.batch-size must be at least 1")
        private int batchSize = 20;

        /**
         * How long a run may go on claiming rows; the step in progress finishes.
         */
        @NotNull(message = "payment.payout.executor.run-budget is required")
        @DurationMin(seconds = 1, message = "payment.payout.executor.run-budget must be at least 1s")
        private Duration runBudget = Duration.ofSeconds(60);

        /**
         * How long a claim owns a row. A lapsed lease lets another executor claim it.
         */
        @NotNull(message = "payment.payout.executor.lease is required")
        @DurationMin(seconds = 10, message = "payment.payout.executor.lease must be at least 10s")
        private Duration lease = Duration.ofMinutes(2);

        /**
         * The wait after the first unknown outcome, doubled after each further one. The first retry comes after any
         * request Stripe was still running, which the lookup before it depends on.
         */
        @NotNull(message = "payment.payout.executor.unknown-backoff is required")
        @DurationMin(seconds = 30, message = "payment.payout.executor.unknown-backoff must be at least 30s")
        private Duration unknownBackoff = Duration.ofMinutes(1);

        @NotNull(message = "payment.payout.executor.unknown-max-backoff is required")
        private Duration unknownMaxBackoff = Duration.ofHours(1);

        /**
         * Tries with an unknown outcome before the payout needs attention, about 7 hours with the default waits.
         */
        @Min(value = 1, message = "payment.payout.executor.unknown-attempts must be at least 1")
        private int unknownAttempts = 13;

        /**
         * The wait after Stripe refuses a payout for the connected account's funds or a payout limit.
         */
        @NotNull(message = "payment.payout.executor.payout-retry-interval is required")
        @DurationMin(seconds = 1, message = "payment.payout.executor.payout-retry-interval must be at least 1s")
        private Duration payoutRetryInterval = Duration.ofHours(2);

        /**
         * Tries of such a payout before the transfer is reversed, 24 hours with the default wait: past a daily payout
         * limit's reset.
         */
        @Min(value = 1, message = "payment.payout.executor.payout-retry-attempts must be at least 1")
        private int payoutRetryAttempts = 13;

        /**
         * The wait after Stripe refuses a reversal.
         */
        @NotNull(message = "payment.payout.executor.reversal-retry-interval is required")
        @DurationMin(seconds = 1, message = "payment.payout.executor.reversal-retry-interval must be at least 1s")
        private Duration reversalRetryInterval = Duration.ofMinutes(30);

        /**
         * Tries of a refused reversal before the payout needs attention, 6 hours with the default wait.
         */
        @Min(value = 1, message = "payment.payout.executor.reversal-retry-attempts must be at least 1")
        private int reversalRetryAttempts = 13;

        @AssertTrue(message = "payment.payout.executor.unknown-max-backoff must not be shorter than unknown-backoff")
        public boolean isUnknownBackoffOrdered() {
            return unknownBackoff == null || unknownMaxBackoff == null
                    || unknownMaxBackoff.compareTo(unknownBackoff) >= 0;
        }

        /**
         * The wait after an unknown outcome, given the step's tries so far: doubled per try, up to the maximum.
         */
        public Duration unknownBackoffAfter(int attempts) {
            Duration doubled = unknownBackoff.multipliedBy(1L << Math.min(Math.max(attempts, 0), 20));
            return doubled.compareTo(unknownMaxBackoff) > 0 ? unknownMaxBackoff : doubled;
        }
    }

    @Getter
    @Setter
    public static class PayoutCurrency {
        @NotNull(message = "payment.payout.currencies[n].code is required")
        @Pattern(regexp = "[A-Z]{3}", message = "payment.payout.currencies[n].code must be three capital letters")
        private String code;

        /**
         * At least Stripe's own minimum payout for the account's country, 5 PLN for PLN in Poland: a lower minimum
         * would be refused at the payout step, after the transfer.
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
