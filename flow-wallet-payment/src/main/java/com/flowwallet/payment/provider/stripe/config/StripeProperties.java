package com.flowwallet.payment.provider.stripe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
         * How far a webhook's timestamp may be from ours before the signature is refused, in seconds. It is a
         * clock-skew allowance: set too small, a drifting host refuses genuine deliveries and their payments
         * are not credited.
         */
        private long toleranceSeconds = 300;
    }
}
