package com.flowwallet.payment.provider.stripe.client;

import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.stripe.net.RequestOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StripeClientTest {
    @Test
    void everyCallCarriesTheConfiguredTimeoutsRetriesAndTheIdempotencyKey() {
        // Guards the options being built without the timeouts, which would leave stripe-java's global defaults of
        // 30 s and 80 s in force, or without the key, which would let a retry create a second intent.
        StripeProperties properties = new StripeProperties();
        properties.getApi().setConnectTimeout(Duration.ofMillis(1500));
        properties.getApi().setReadTimeout(Duration.ofSeconds(4));
        properties.getApi().setMaxNetworkRetries(1);

        RequestOptions options = new StripeClient(properties).requestOptions("ref-1");

        assertThat(options.getIdempotencyKey()).isEqualTo("ref-1");
        assertThat(options.getConnectTimeout()).isEqualTo(1500);
        assertThat(options.getReadTimeout()).isEqualTo(4000);
        assertThat(options.getMaxNetworkRetries()).isEqualTo(1);
    }
}
