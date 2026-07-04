package com.flowwallet.payment.provider.stripe;

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
    public PaymentInitiationResult initiatePayment(PaymentRequestContext context) {
        try {
            PaymentIntentCreateParams params = requestMapper.toPaymentIntentParams(context);
            PaymentIntent paymentIntent = stripeClient.createPaymentIntent(params);

            return new PaymentInitiationResult(paymentIntent.getId(), paymentIntent.getClientSecret());
        } catch (StripeException e) {
            log.error("Failed to initiate Stripe payment for transaction: {}", context.transactionReference(), e);
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
