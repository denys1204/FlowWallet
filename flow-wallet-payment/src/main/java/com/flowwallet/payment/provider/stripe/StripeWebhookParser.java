package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static com.flowwallet.payment.provider.stripe.StripeConstants.HEADER_SIGNATURE;

/**
 * Verifies and parses a raw Stripe webhook request into a {@link ParsedStripeEvent}. The signature is checked
 * before the body is parsed, so an unauthenticated body is never read as JSON, and every refusal is an
 * {@link InvalidWebhookSignatureException}. See docs/adr/0017-webhooks-verified-before-they-are-read.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookParser {
    private final StripeClient stripeClient;

    public ParsedStripeEvent parse(String payload, Map<String, String> headers) {
        if (!stripeClient.isWebhookVerificationEnabled()) {
            throw new InvalidWebhookSignatureException("Webhook signing secret is not configured");
        }
        String signature = extractSignature(headers);
        verifySignature(payload, signature);
        return read(payload, signature);
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
    private void verifySignature(String payload, String signature) {
        try {
            stripeClient.verifyWebhookSignature(payload, signature);
        } catch (SignatureVerificationException | RuntimeException e) {
            throw new InvalidWebhookSignatureException("Invalid Stripe signature", e);
        }
    }

    private ParsedStripeEvent read(String payload, String signature) {
        Event event;
        EventDataObjectDeserializer deserializer;
        Optional<StripeObject> matchingVersionObject;
        try {
            event = stripeClient.constructVerifiedEvent(payload, signature);
            deserializer = event.getDataObjectDeserializer();
            matchingVersionObject = deserializer.getObject();
        } catch (SignatureVerificationException e) {
            throw new InvalidWebhookSignatureException("Invalid Stripe signature", e);
        } catch (RuntimeException e) {
            throw new WebhookProcessingException("Signed Stripe webhook is not a readable event", e);
        }
        StripeObject dataObject = matchingVersionObject.orElseGet(() -> deserializeUnsafe(event, deserializer));
        return new ParsedStripeEvent(event.getId(), event.getType(), createdAt(event), dataObject);
    }

    /**
     * Stripe sets {@code created} on every event; the processing time stands in for a payload without it.
     */
    private Instant createdAt(Event event) {
        return event.getCreated() == null ? Instant.now() : Instant.ofEpochSecond(event.getCreated());
    }

    private StripeObject deserializeUnsafe(Event event, EventDataObjectDeserializer deserializer) {
        log.warn(
                "Stripe API version mismatch! Using deserializeUnsafe(). "
                        + "Please update the Stripe Java SDK to match the dashboard version. Event ID: {}",
                event.getId()
        );

        try {
            return deserializer.deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            throw new WebhookProcessingException("Failed to deserialize Stripe event data", e);
        }
    }
}
