package com.flowwallet.payment.provider.stripe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Everything Stripe needs to be told. Nested to keep the existing {@code stripe.api.key} and
 * {@code stripe.webhook.secret} keys exactly as they were, so no deployment has to relearn a variable.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {
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
        /**
         * Signing secret used to verify that a webhook really came from Stripe.
         */
        private String secret = "whsec_dummy";

        /**
         * How far a webhook's timestamp may be from ours before the signature is refused, in seconds.
         * <p>
         * This is a clock-skew allowance, not a security parameter to tighten blindly: too small and a
         * drifting host starts rejecting genuine deliveries, which surfaces as payments that never
         * credit. Worth changing without a rebuild, because the moment you need to is an incident.
         */
        private long toleranceSeconds = 300;
    }
}
