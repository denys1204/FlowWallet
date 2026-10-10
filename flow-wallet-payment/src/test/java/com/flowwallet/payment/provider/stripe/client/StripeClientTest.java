package com.flowwallet.payment.provider.stripe.client;

import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeClientTest {
    private static final String PLATFORM_SECRET = "whsec_unit-test-signing-secret";
    private static final String CONNECT_SECRET = "whsec_unit-test-connect-secret";
    private static final String PAYLOAD = """
            {"id": "evt_1", "object": "event", "created": 1767225600, "type": "payout.paid"}
            """;

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

    @Test
    void aBodySignedWithThePlatformSecretFailsAgainstTheConnectSecret() throws Exception {
        // Guards the two endpoints sharing a key: a delivery signed for the platform endpoint would be accepted by
        // the Connect one, and the secret a call names would be ignored in favour of a configured one.
        StripeClient client = new StripeClient(new StripeProperties());
        String header = signatureHeader(PAYLOAD, PLATFORM_SECRET, Webhook.Util.getTimeNow());

        assertThatThrownBy(() -> client.verifyWebhookSignature(PAYLOAD, header, CONNECT_SECRET))
                .isInstanceOf(SignatureVerificationException.class);
        assertThatThrownBy(() -> client.constructVerifiedEvent(PAYLOAD, header, CONNECT_SECRET))
                .isInstanceOf(SignatureVerificationException.class);
        assertThatCode(() -> client.verifyWebhookSignature(PAYLOAD, header, PLATFORM_SECRET))
                .doesNotThrowAnyException();
        assertThat(client.constructVerifiedEvent(PAYLOAD, header, PLATFORM_SECRET).getId()).isEqualTo("evt_1");
    }

    @Test
    void theSignaturesTimestampIsCheckedAgainstTheConfiguredToleranceForAnySecret() throws Exception {
        // Guards a tolerance fixed in the code or read from another setting: a drifting host would refuse genuine
        // deliveries, or a captured one would stay valid for longer than the configured window.
        String tenMinutesOld = signatureHeader(PAYLOAD, CONNECT_SECRET, Webhook.Util.getTimeNow() - 600);
        StripeProperties strict = new StripeProperties();
        strict.getWebhook().setToleranceSeconds(300);
        StripeProperties lenient = new StripeProperties();
        lenient.getWebhook().setToleranceSeconds(900);

        StripeClient strictClient = new StripeClient(strict);
        assertThatThrownBy(() -> strictClient.verifyWebhookSignature(PAYLOAD, tenMinutesOld, CONNECT_SECRET))
                .isInstanceOf(SignatureVerificationException.class);
        assertThatThrownBy(() -> strictClient.constructVerifiedEvent(PAYLOAD, tenMinutesOld, CONNECT_SECRET))
                .isInstanceOf(SignatureVerificationException.class);
        StripeClient lenientClient = new StripeClient(lenient);
        assertThatCode(() -> lenientClient.verifyWebhookSignature(PAYLOAD, tenMinutesOld, CONNECT_SECRET))
                .doesNotThrowAnyException();
        assertThat(lenientClient.constructVerifiedEvent(PAYLOAD, tenMinutesOld, CONNECT_SECRET).getId())
                .isEqualTo("evt_1");
    }

    private static String signatureHeader(String payload, String secret, long timestamp) throws Exception {
        return "t=" + timestamp + ",v1=" + Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);
    }
}
