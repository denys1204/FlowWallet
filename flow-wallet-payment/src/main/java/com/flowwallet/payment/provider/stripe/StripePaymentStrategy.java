package com.flowwallet.payment.provider.stripe;

import static com.flowwallet.payment.provider.stripe.StripeConstants.EVENT_PAYMENT_FAILED;
import static com.flowwallet.payment.provider.stripe.StripeConstants.EVENT_PAYMENT_SUCCEEDED;
import static com.flowwallet.payment.provider.stripe.StripeConstants.HEADER_SIGNATURE;
import static com.flowwallet.payment.provider.stripe.StripeConstants.RESPONSE_CLIENT_SECRET;

import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.PaymentInitiationException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.flowwallet.payment.provider.stripe.mapper.StripeRequestMapper;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripePaymentStrategy implements PaymentProviderStrategy {
    private final StripeRequestMapper requestMapper;
    private final StripeClient stripeClient;

    @Override
    public boolean supports(PaymentProvider provider) {
        return PaymentProvider.STRIPE == provider;
    }

    @Override
    public PaymentInitiationResult initiatePayment(PaymentRequestContext context) {
        try {
            var params = requestMapper.toPaymentIntentParams(context);
            var paymentIntent = stripeClient.createPaymentIntent(params);

            return new PaymentInitiationResult(
                paymentIntent.getId(),
                Map.of(RESPONSE_CLIENT_SECRET, paymentIntent.getClientSecret())
            );
        } catch (StripeException e) {
            log.error("Failed to initiate Stripe payment for transaction: {}", context.transactionReference(), e);
            throw new PaymentInitiationException("Stripe payment initiation failed", e);
        }
    }

    @Override
    public WebhookResult handleWebhook(String payload, Map<String, String> headers) {
        String signature = extractSignature(headers);
        Event event = parseEventOrThrow(payload, signature);
        StripeObject stripeObject = deserializeEventData(event);

        if (!(stripeObject instanceof PaymentIntent paymentIntent)) {
            log.debug("Ignoring non-PaymentIntent Stripe object for event: {}", event.getType());
            return WebhookResult.unknown();
        }

        WebhookEventType eventType = resolveEventType(event.getType());
        if (eventType == WebhookEventType.UNKNOWN) {
            log.debug("Unhandled Stripe event type: {}", event.getType());
            return WebhookResult.unknown();
        }

        return new WebhookResult(paymentIntent.getId(), event.getId(), eventType);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private StripeObject deserializeEventData(Event event) {
        return event.getDataObjectDeserializer().getObject()
                .orElseGet(() -> deserializeUnsafe(event));
    }

    private StripeObject deserializeUnsafe(Event event) {
        log.warn("Stripe API version mismatch! Using deserializeUnsafe(). " +
                "Please update the Stripe Java SDK to match the dashboard version. Event ID: {}", event.getId()
        );

        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            throw new WebhookProcessingException("Failed to deserialize Stripe event data", e);
        }
    }

    private WebhookEventType resolveEventType(String stripeEventType) {
        return switch (stripeEventType) {
            case EVENT_PAYMENT_SUCCEEDED -> WebhookEventType.PAYMENT_SUCCESS;
            case EVENT_PAYMENT_FAILED -> WebhookEventType.PAYMENT_FAILURE;
            default -> WebhookEventType.UNKNOWN;
        };
    }

    private String extractSignature(Map<String, String> headers) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(HEADER_SIGNATURE))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(
                        () -> new InvalidWebhookSignatureException("Missing Stripe signature header")
                );
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
}
