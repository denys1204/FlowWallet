package com.flowwallet.payment.provider.stripe.client;

import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void aConnectCallNamesTheConnectedAccountAndKeepsTheTimeouts() {
        // Guards a payout made on the platform's own balance because the account header was dropped, and a Connect
        // call that waits on stripe-java's global defaults.
        StripeProperties properties = new StripeProperties();
        properties.getApi().setReadTimeout(Duration.ofSeconds(4));

        StripeClient client = new StripeClient(properties);
        RequestOptions connect = client.requestOptions("wd-payout-ref-1-0", "acct_unit_test");
        RequestOptions platform = client.requestOptions("ref-1");

        assertThat(connect.getStripeAccount()).isEqualTo("acct_unit_test");
        assertThat(connect.getIdempotencyKey()).isEqualTo("wd-payout-ref-1-0");
        assertThat(connect.getReadTimeout()).isEqualTo(4000);
        assertThat(platform.getStripeAccount()).isNull();
    }

    @Test
    void aFailureOnALaterPageSurfacesAsTheStripeException() {
        // Guards a page that fails mid-list escaping as a bare RuntimeException past the lookup's catch, so the caller
        // would see neither a lookup failure nor a complete list.
        ApiConnectionException timeout = new ApiConnectionException("Read timed out");
        Iterable<String> pages = () -> new Iterator<>() {
            private boolean first = true;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public String next() {
                if (first) {
                    first = false;
                    return "tr_1";
                }
                throw new RuntimeException("Unable to lazy-load stripe objects", timeout);
            }
        };

        assertThatThrownBy(() -> StripeClient.collect(pages)).isSameAs(timeout).isInstanceOf(StripeException.class);
    }
}
