package com.flowwallet.payment.provider.stripe.config;

import com.stripe.Stripe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripeConfigTest {
    private String apiKeyBefore;
    private boolean telemetryBefore;

    @BeforeEach
    void rememberTheSdkState() {
        apiKeyBefore = Stripe.apiKey;
        telemetryBefore = Stripe.enableTelemetry;
    }

    @AfterEach
    void restoreTheSdkState() {
        // Both are static fields of the SDK, shared by every test in the JVM.
        Stripe.apiKey = apiKeyBefore;
        Stripe.enableTelemetry = telemetryBefore;
    }

    @Test
    void startupTurnsOffTheSdkTelemetryAndSetsTheKey() {
        // Guards a removed or reordered line in initStripe: with telemetry on, stripe-java sends the host's OS details,
        // a per-installation id and request latencies with every call.
        Stripe.enableTelemetry = true;
        StripeProperties properties = new StripeProperties();
        properties.getApi().setKey("sk_test_unit-test-key");

        new StripeConfig(properties).initStripe();

        assertThat(Stripe.enableTelemetry).isFalse();
        assertThat(Stripe.apiKey).isEqualTo("sk_test_unit-test-key");
    }
}
