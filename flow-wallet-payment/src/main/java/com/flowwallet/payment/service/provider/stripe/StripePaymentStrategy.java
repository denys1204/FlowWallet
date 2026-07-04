package com.flowwallet.payment.service.provider.stripe;

import com.flowwallet.payment.entity.PaymentTransaction;
import com.flowwallet.payment.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.exception.PaymentInitiationException;
import com.flowwallet.payment.exception.WebhookProcessingException;
import com.flowwallet.payment.service.provider.PaymentProvider;
import com.flowwallet.payment.service.provider.PaymentProviderStrategy;
import com.flowwallet.payment.service.provider.WebhookEventType;
import com.flowwallet.payment.service.provider.WebhookResult;
import com.flowwallet.payment.service.provider.stripe.client.StripeClient;
import com.flowwallet.payment.service.provider.stripe.mapper.StripeRequestMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.param.PaymentIntentCreateParams;
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
    public String initiatePayment(PaymentTransaction transaction) {
        try {
            PaymentIntentCreateParams params = requestMapper.toPaymentIntentParams(transaction);
            PaymentIntent paymentIntent = stripeClient.createPaymentIntent(params);

            transaction.setProviderTransactionId(paymentIntent.getId());

            return paymentIntent.getClientSecret();
        } catch (StripeException e) {
            log.error("Failed to initiate Stripe payment for transaction: {}", transaction.getTransactionReference(), e);
            throw new PaymentInitiationException("Stripe payment initiation failed", e);
        }
    }

    @Override
    public WebhookResult handleWebhook(String payload, Map<String, String> headers) {
        String signature = extractSignature(headers);
        Event event = parseEventOrThrow(payload, signature);

        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(stripeObject instanceof PaymentIntent paymentIntent)) {
            log.debug("Unhandled Stripe object type for event: {}", event.getType());
            return WebhookResult.unknown();
        }

        WebhookEventType eventType = switch (event.getType()) {
            case "payment_intent.succeeded" -> WebhookEventType.PAYMENT_SUCCESS;
            case "payment_intent.payment_failed" -> WebhookEventType.PAYMENT_FAILURE;
            default -> WebhookEventType.UNKNOWN;
        };

        if (eventType == WebhookEventType.UNKNOWN) {
            log.debug("Unhandled Stripe event type: {}", event.getType());
            return WebhookResult.unknown();
        }

        return new WebhookResult(paymentIntent.getId(), event.getId(), eventType);
    }

    private String extractSignature(Map<String, String> headers) {
        return headers.entrySet().stream()
                .filter(e -> "stripe-signature".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new InvalidWebhookSignatureException("Missing Stripe signature header", null));
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
