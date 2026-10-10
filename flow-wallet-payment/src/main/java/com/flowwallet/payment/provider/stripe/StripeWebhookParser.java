package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.stripe.Stripe;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static com.flowwallet.payment.provider.stripe.StripeConstants.HEADER_SIGNATURE;

/**
 * Verifies and parses a raw Stripe webhook request into a {@link ParsedStripeEvent}, with the secret of the endpoint
 * it reached. The signature is checked before the body is parsed, so an unauthenticated body is never read as JSON,
 * and every refusal is an {@link InvalidWebhookSignatureException}.
 * See docs/adr/0017-webhooks-verified-before-they-are-read.md and
 * docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookParser {
    private final StripeClient stripeClient;

    public ParsedStripeEvent parse(String payload, Map<String, String> headers, @Nullable String secret) {
        if (!StripeProperties.Webhook.isSigningSecret(secret)) {
            throw new InvalidWebhookSignatureException("Webhook signing secret is not configured");
        }
        String signature = extractSignature(headers);
        verifySignature(payload, signature, secret);
        return read(payload, signature, secret);
    }

    private String extractSignature(Map<String, String> headers) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(HEADER_SIGNATURE))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new InvalidWebhookSignatureException("Missing Stripe signature header"));
    }

    /**
     * A malformed header ({@code t=abc}, a bare {@code t}) makes the SDK throw a {@code RuntimeException} while it
     * splits the header, so that counts as a bad signature too.
     */
    private void verifySignature(String payload, String signature, String secret) {
        try {
            stripeClient.verifyWebhookSignature(payload, signature, secret);
        } catch (SignatureVerificationException | RuntimeException e) {
            throw new InvalidWebhookSignatureException("Invalid Stripe signature", e);
        }
    }

    private ParsedStripeEvent read(String payload, String signature, String secret) {
        Event event;
        EventDataObjectDeserializer deserializer;
        Optional<StripeObject> matchingVersionObject;
        try {
            event = stripeClient.constructVerifiedEvent(payload, signature, secret);
            deserializer = event.getDataObjectDeserializer();
            matchingVersionObject = deserializer.getObject();
        } catch (SignatureVerificationException e) {
            throw new InvalidWebhookSignatureException("Invalid Stripe signature", e);
        } catch (RuntimeException e) {
            throw new WebhookProcessingException("Signed Stripe webhook is not a readable event", e);
        }
        StripeObject dataObject = matchingVersionObject.orElseGet(() -> deserializeUnsafe(event, deserializer));
        return new ParsedStripeEvent(event.getId(), event.getType(), createdAt(event), event.getAccount(), dataObject);
    }

    /**
     * Stripe sets {@code created} on every event; the processing time stands in for a payload without it.
     */
    private Instant createdAt(Event event) {
        return event.getCreated() == null ? Instant.now() : Instant.ofEpochSecond(event.getCreated());
    }

    /**
     * An event in another API version than the SDK's: under {@code stripe listen} the account's default version, or an
     * endpoint registered without the SDK's version. The remedy is the endpoint's version, never the SDK's.
     */
    private StripeObject deserializeUnsafe(Event event, EventDataObjectDeserializer deserializer) {
        log.warn(
                "Stripe event {} has API version {}, and this service reads {}: its data object is read with "
                        + "deserializeUnsafe(). Register the endpoint with API version {} to read it as the SDK "
                        + "models it",
                event.getId(),
                event.getApiVersion(),
                Stripe.API_VERSION,
                Stripe.API_VERSION
        );

        try {
            return deserializer.deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            throw new WebhookProcessingException("Failed to deserialize Stripe event data", e);
        }
    }
}
