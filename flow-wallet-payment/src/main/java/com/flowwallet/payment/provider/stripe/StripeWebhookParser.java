package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.flowwallet.payment.provider.stripe.StripeConstants.HEADER_SIGNATURE;

/**
 * Verifies and parses a raw Stripe webhook request into a provider-agnostic {@link ParsedStripeEvent}.
 * <p>
 * Extracted from {@link StripePaymentStrategy} so the strategy stays thin and this Stripe wire-format
 * handling — signature extraction, event construction/verification, and data-object deserialization
 * (with the API-version-mismatch fallback) — is a single-responsibility collaborator that can be unit
 * tested in isolation with a mocked {@link StripeClient}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookParser {
    private final StripeClient stripeClient;

    public ParsedStripeEvent parse(String payload, Map<String, String> headers) {
        String signature = extractSignature(headers);
        Event event = parseEventOrThrow(payload, signature);
        StripeObject dataObject = deserializeEventData(event);
        return new ParsedStripeEvent(event.getId(), event.getType(), dataObject);
    }

    private String extractSignature(Map<String, String> headers) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(HEADER_SIGNATURE))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new InvalidWebhookSignatureException("Missing Stripe signature header"));
    }

    private Event parseEventOrThrow(String payload, String signature) {
        try {
            return stripeClient.constructEvent(payload, signature);
        } catch (SignatureVerificationException e) {
            log.error("Invalid Stripe signature", e);
            throw new InvalidWebhookSignatureException("Invalid Stripe signature", e);
        } catch (RuntimeException e) {
            log.error("Error processing Stripe webhook payload", e);
            throw new WebhookProcessingException("Error processing webhook", e);
        }
    }

    private StripeObject deserializeEventData(Event event) {
        return event.getDataObjectDeserializer().getObject().orElseGet(
                () -> deserializeUnsafe(event)
        );
    }

    private StripeObject deserializeUnsafe(Event event) {
        log.warn(
                "Stripe API version mismatch! Using deserializeUnsafe(). "
                        + "Please update the Stripe Java SDK to match the dashboard version. Event ID: {}",
                event.getId()
        );

        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            throw new WebhookProcessingException("Failed to deserialize Stripe event data", e);
        }
    }
}
