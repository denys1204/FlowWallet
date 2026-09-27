package com.flowwallet.payment.provider.stripe.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {
    @Valid
    private final Webhook webhook = new Webhook();
    private final Api api = new Api();

    @Getter
    @Setter
    public static class Api {
        /**
         * Secret API key. The dummy default lets the application start without credentials.
         */
        private String key = "sk_test_dummy";
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
         * are not credited. stripe-java skips the replay check entirely for a value of zero or less, so one
         * fails startup.
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
