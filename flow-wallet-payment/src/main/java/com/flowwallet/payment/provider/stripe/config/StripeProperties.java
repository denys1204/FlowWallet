package com.flowwallet.payment.provider.stripe.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {
    @Valid
    private final Webhook webhook = new Webhook();

    @Valid
    private final Api api = new Api();

    /**
     * The payment methods a deposit intent offers. A name {@link StripePaymentMethodType} does not list fails
     * binding and an empty set fails validation, so a mistyped value stops startup instead of offering methods
     * nobody chose. See docs/adr/0028-deposits-accept-cards-only.md.
     */
    @NotEmpty(message = "stripe.payment-method-types must name at least one payment method")
    private Set<StripePaymentMethodType> paymentMethodTypes = EnumSet.of(StripePaymentMethodType.CARD);

    /**
     * How the API calls reach Stripe. The timeouts and retries replace stripe-java's defaults (30 s to connect,
     * 80 s to read, two retries), which kept a Payment Service thread busy long after Wallet Service had given up
     * on it. {@code (1 + maxNetworkRetries) * (connectTimeout + readTimeout)}, plus about half a second of backoff
     * per retry, should stay below the wallet's {@code wallet.payment.read-timeout}.
     * See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
     */
    @Getter
    @Setter
    public static class Api {
        /**
         * Secret API key. The dummy default lets the application start without credentials.
         */
        private String key = "sk_test_dummy";

        /**
         * stripe-java takes whole milliseconds as an {@code int}, which bounds both timeouts from above.
         */
        @NotNull
        @DurationMin(millis = 1, message = "stripe.api.connect-timeout must be at least 1ms")
        @DurationMax(millis = Integer.MAX_VALUE, message = "stripe.api.connect-timeout must fit an int of millis")
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull
        @DurationMin(millis = 1, message = "stripe.api.read-timeout must be at least 1ms")
        @DurationMax(millis = Integer.MAX_VALUE, message = "stripe.api.read-timeout must fit an int of millis")
        private Duration readTimeout = Duration.ofSeconds(6);

        /**
         * stripe-java retries a connection failure, a timeout, a 409 and a 5xx under the same idempotency key. Zero
         * leaves the retry to the client, which repeats the deposit with its key.
         */
        @PositiveOrZero(message = "stripe.api.max-network-retries must not be negative")
        private int maxNetworkRetries = 0;
    }

    @Getter
    @Setter
    public static class Webhook {
        private static final String SECRET_PREFIX = "whsec_";

        /**
         * Values that shipped as defaults or in {@code .env.example}. stripe-java signs with any string, so a
         * published value would let anyone sign a webhook.
         */
        private static final Set<String> PLACEHOLDERS = Set.of("whsec_dummy", "whsec_your_secret_here");

        /**
         * Signing secret used to verify that a webhook really came from Stripe. It has no default: while it is
         * unset, blank, a known placeholder or not a {@code whsec_} value, webhooks are disabled and every
         * delivery is refused. See docs/adr/0017-webhooks-verified-before-they-are-read.md.
         */
        private String secret;

        /**
         * How far a webhook's timestamp may be from ours before the signature is refused, in seconds. It is a
         * clock-skew allowance: set too small, a drifting host refuses genuine deliveries and their payments
         * wait for the reconciler. stripe-java skips the replay check entirely for a value of zero or less, so
         * one fails startup.
         */
        @Positive(message = "stripe.webhook.tolerance-seconds must be greater than zero")
        private long toleranceSeconds = 300;

        public boolean hasSigningSecret() {
            return secret != null
                    && secret.startsWith(SECRET_PREFIX)
                    && secret.length() > SECRET_PREFIX.length()
                    && !PLACEHOLDERS.contains(secret);
        }
    }
}
