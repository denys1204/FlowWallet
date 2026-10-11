package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.flowwallet.payment.support.StripeSignatures;
import com.stripe.Stripe;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Signs payloads with stripe-java's own HMAC helper and a real secret, so the tests exercise the SDK's header
 * parsing and verification rather than a mock of it.
 */
class StripeWebhookParserTest {
    private static final String SECRET = "whsec_unit-test-signing-secret";
    private static final String CONNECT_SECRET = "whsec_unit-test-connect-secret";
    private static final long CREATED = 1_767_225_600L;

    @Test
    void aPayloadSignedWithTheConfiguredSecretIsParsedIntoItsEventAndPaymentIntent() throws Exception {
        // Guards against a legitimately signed webhook failing verification or its fields being misparsed.
        String payload = succeededEvent(Stripe.API_VERSION);

        ParsedStripeEvent result = parser().parse(payload, StripeSignatures.header(payload, SECRET), SECRET);

        assertThat(result.eventId()).isEqualTo("evt_1");
        assertThat(result.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(result.created()).isEqualTo(Instant.ofEpochSecond(CREATED));
        assertThat(result.dataObject()).isInstanceOfSatisfying(PaymentIntent.class, intent -> {
            assertThat(intent.getId()).isEqualTo("pi_1");
            assertThat(intent.getAmount()).isEqualTo(5000L);
            assertThat(intent.getCurrency()).isEqualTo("usd");
            assertThat(intent.getStatus()).isEqualTo("succeeded");
        });
        assertThat(result.account()).isNull();
    }

    @Test
    void anEventFromAConnectedAccountCarriesTheAccountAndThePayout() throws Exception {
        // The Connect endpoint settles a payout on the account the event names; without it the event cannot be
        // matched to a payout, and the payout waits for the reconciler.
        String payload = payoutPaidEvent(Stripe.API_VERSION);

        ParsedStripeEvent result = parser().parse(payload, StripeSignatures.header(payload, SECRET), SECRET);

        assertThat(result.eventType()).isEqualTo("payout.paid");
        assertThat(result.account()).isEqualTo("acct_1");
        assertThat(result.objectId()).isEqualTo("po_1");
        assertThat(result.dataObject()).isInstanceOf(Payout.class);
    }

    @Test
    void verifiesWithTheSecretItIsGiven() throws Exception {
        // Guards the parser verifying with a secret of its own instead of the one the endpoint passes: a body signed
        // for the Connect endpoint would then fail there, or pass at the deposit endpoint.
        String payload = payoutPaidEvent(Stripe.API_VERSION);
        Map<String, String> signedWithConnectSecret = StripeSignatures.header(payload, CONNECT_SECRET);

        ParsedStripeEvent result = parser().parse(payload, signedWithConnectSecret, CONNECT_SECRET);

        assertThat(result.eventId()).isEqualTo("evt_2");
        assertThatThrownBy(() -> parser().parse(payload, signedWithConnectSecret, SECRET))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessage("Invalid Stripe signature");
    }

    @Test
    void aPayloadSignedWithAnotherSecretIsRefusedAsABadSignature() throws Exception {
        // Guards against a signature computed with the wrong secret being accepted as genuine.
        String payload = succeededEvent(Stripe.API_VERSION);

        assertThatThrownBy(() -> parser().parse(
                payload,
                StripeSignatures.header(payload, "whsec_someone_elses_secret"),
                SECRET
        )).isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void aSignatureOlderThanTheToleranceIsRefused() throws Exception {
        // The replay window: a captured delivery must not be accepted again once its timestamp has aged out.
        String payload = succeededEvent(Stripe.API_VERSION);
        long tooOld = Webhook.Util.getTimeNow() - 301;

        assertThatThrownBy(() -> parser().parse(payload, StripeSignatures.header(payload, SECRET, tooOld), SECRET))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @ParameterizedTest(name = "secret \"{0}\" disables webhooks")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "whsec_dummy", "whsec_your_secret_here", "whsec_", "sk_test_123", "secret"})
    void withoutARealSigningSecretEveryDeliveryIsRefusedWithoutVerifying(String secret) throws Exception {
        // stripe-java computes an HMAC with any string as the key. With a published placeholder as the secret,
        // anyone could sign a payment_intent.succeeded for their own intent and be credited without paying, so
        // a payload signed with that very placeholder must still be refused.
        StripeClient client = spy(new StripeClient(new StripeProperties()));
        StripeWebhookParser parser = new StripeWebhookParser(client);
        String payload = succeededEvent(Stripe.API_VERSION);
        // An HMAC key cannot be empty, so the unset cases are signed with an arbitrary one.
        String signingKey = secret == null || secret.isEmpty() ? "whsec_any" : secret;

        assertThatThrownBy(() -> parser.parse(payload, StripeSignatures.header(payload, signingKey), secret))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessage("Webhook signing secret is not configured");
        verify(client, never()).verifyWebhookSignature(anyString(), anyString(), any());
        verify(client, never()).constructVerifiedEvent(anyString(), anyString(), any());
    }

    @ParameterizedTest(name = "header \"{0}\" is a bad signature")
    @ValueSource(strings = {"t=abc,v1=00", "t", "v1", "t=", "t=1767225600", "v1=00", "", "garbage"})
    void aMalformedSignatureHeaderIsRefusedAsABadSignature(String header) {
        // stripe-java throws NumberFormatException or ArrayIndexOutOfBoundsException for some of these while it
        // splits the header. Escaping as a 500, they would tell the sender to retry a request that was never
        // authenticated.
        String payload = succeededEvent(Stripe.API_VERSION);

        assertThatThrownBy(() -> parser().parse(payload, Map.of("Stripe-Signature", header), SECRET))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void aMissingSignatureHeaderIsRefusedAsABadSignature() {
        // Guards against a request with no signature header being processed as though it were verified.
        assertThatThrownBy(() -> parser().parse(succeededEvent(Stripe.API_VERSION), Map.of(), SECRET))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void anUnsignedBodyThatIsNotJsonIsRefusedAsABadSignatureBecauseItIsNeverParsed() {
        // stripe-java's constructEvent parses the body before it checks the signature, so called first it hands
        // an unauthenticated body to the JSON parser and a broken one comes back as a 500. Verifying first keeps
        // it a 400.
        assertThatThrownBy(() -> parser().parse(
                "{not json",
                Map.of("Stripe-Signature", "t=1767225600,v1=00"),
                SECRET
        )).isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void aSignedBodyThatIsNotAnEventIsAProcessingFailure() throws Exception {
        // Only the holder of the secret can get this far, so the fault is on this side and a 500 is right.
        String payload = "{not json";

        assertThatThrownBy(() -> parser().parse(payload, StripeSignatures.header(payload, SECRET), SECRET))
                .isInstanceOf(WebhookProcessingException.class);
    }

    @Test
    void anEventFromAnotherApiVersionFallsBackToUnsafeDeserialization() throws Exception {
        // Guards against an event from an API version stripe-java does not model failing to parse instead of
        // falling back to unsafe deserialization.
        String payload = succeededEvent("2020-08-27");

        ParsedStripeEvent result = parser().parse(payload, StripeSignatures.header(payload, SECRET), SECRET);

        assertThat(result.dataObject()).isInstanceOf(PaymentIntent.class);
    }

    @Test
    void aMismatchedVersionKeepsAccountAndObjectId() throws Exception {
        // Guards the version fallback dropping the envelope: stripe listen delivers events in the account's default
        // version, which differs from the SDK's, and the Connect handler needs the account and the payout id from
        // exactly those events.
        String payload = payoutPaidEvent("2025-12-15.clover");
        Map<String, String> headers = StripeSignatures.header(payload, SECRET);
        Event typed = new StripeClient(new StripeProperties())
                .constructVerifiedEvent(payload, headers.get("Stripe-Signature"), SECRET);
        assertThat(typed.getDataObjectDeserializer().getObject()).isEmpty();

        ParsedStripeEvent result = parser().parse(payload, headers, SECRET);

        assertThat(result.eventType()).isEqualTo("payout.paid");
        assertThat(result.account()).isEqualTo("acct_1");
        assertThat(result.objectId()).isEqualTo("po_1");
        assertThat(result.dataObject()).isInstanceOf(Payout.class);
    }

    @Test
    void aFailedUnsafeDeserializationIsAProcessingFailure() throws Exception {
        // Guards against a broken unsafe-deserialization fallback being swallowed instead of surfaced.
        StripeClient client = mock(StripeClient.class);
        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(client.constructVerifiedEvent("payload", "sig", SECRET)).thenReturn(event);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.deserializeUnsafe()).thenThrow(
                new EventDataObjectDeserializationException("version mismatch", null)
        );

        assertThatThrownBy(() -> new StripeWebhookParser(client).parse(
                "payload",
                Map.of("Stripe-Signature", "sig"),
                SECRET
        )).isInstanceOf(WebhookProcessingException.class);
    }

    private static StripeWebhookParser parser() {
        return new StripeWebhookParser(new StripeClient(new StripeProperties()));
    }

    private static String succeededEvent(String apiVersion) {
        return """
                {
                  "id": "evt_1",
                  "object": "event",
                  "api_version": "%s",
                  "created": %d,
                  "type": "payment_intent.succeeded",
                  "data": {
                    "object": {
                      "id": "pi_1",
                      "object": "payment_intent",
                      "amount": 5000,
                      "currency": "usd",
                      "status": "succeeded"
                    }
                  }
                }
                """.formatted(apiVersion, CREATED);
    }

    private static String payoutPaidEvent(String apiVersion) {
        return """
                {
                  "id": "evt_2",
                  "object": "event",
                  "api_version": "%s",
                  "account": "acct_1",
                  "created": %d,
                  "type": "payout.paid",
                  "data": {
                    "object": {
                      "id": "po_1",
                      "object": "payout",
                      "amount": 2500,
                      "currency": "pln",
                      "status": "paid"
                    }
                  }
                }
                """.formatted(apiVersion, CREATED);
    }
}
