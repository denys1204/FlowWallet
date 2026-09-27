package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import com.flowwallet.payment.provider.exception.PaymentInitiationException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.flowwallet.payment.provider.stripe.mapper.StripeRequestMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import static com.flowwallet.payment.provider.stripe.StripeConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripePaymentStrategy implements PaymentProviderStrategy {
    private final StripeRequestMapper requestMapper;
    private final StripeWebhookParser webhookParser;
    private final StripeClient stripeClient;

    @Override
    public boolean supports(PaymentProvider provider) {
        return PaymentProvider.STRIPE == provider;
    }

    @Override
    public void validateRequest(PaymentRequestContext context) {
        requestMapper.validate(context);
    }

    @Override
    public PaymentInitiationResult initiatePayment(PaymentRequestContext context) {
        try {
            var params = requestMapper.toPaymentIntentParams(context);
            // Use the transaction reference as Stripe's idempotency key so a retry never creates a duplicate intent.
            var paymentIntent = stripeClient.createPaymentIntent(params, context.transactionReference());

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
        ParsedStripeEvent event = webhookParser.parse(payload, headers);

        if (!(event.dataObject() instanceof PaymentIntent paymentIntent)) {
            log.debug("Ignoring non-PaymentIntent Stripe object for event: {}", event.eventType());
            return WebhookResult.unknown();
        }

        WebhookEventType eventType = resolveEventType(event.eventType());
        if (eventType == WebhookEventType.UNKNOWN) {
            log.debug("Unhandled Stripe event type: {}", event.eventType());
            return WebhookResult.unknown();
        }

        // A success event must describe a settled intent. Anything else is acknowledged and applied to no row.
        // See docs/adr/0017-webhooks-verified-before-they-are-read.md.
        if (eventType == WebhookEventType.PAYMENT_SUCCESS && !STATUS_SUCCEEDED.equals(paymentIntent.getStatus())) {
            log.error(
                    "Ignoring success event {} for provider tx {}: the PaymentIntent's status is {}",
                    event.eventId(),
                    paymentIntent.getId(),
                    paymentIntent.getStatus()
            );
            return WebhookResult.unknown();
        }

        return new WebhookResult(
                paymentIntent.getId(),
                event.eventId(),
                eventType,
                majorUnitAmount(paymentIntent),
                paymentIntent.getCurrency() == null ? null : paymentIntent.getCurrency().toUpperCase(Locale.ROOT)
        );
    }

    /**
     * The inverse of the conversion in {@link StripeRequestMapper}, through the same transmit exponent, so a
     * stored 12.34 KWD matches Stripe's 12340. Null when the intent carries no amount or currency.
     */
    private BigDecimal majorUnitAmount(PaymentIntent paymentIntent) {
        if (paymentIntent.getAmount() == null || paymentIntent.getCurrency() == null) {
            return null;
        }
        int exponent = StripeCurrencyRules.of(paymentIntent.getCurrency()).transmitExponent();
        return BigDecimal.valueOf(paymentIntent.getAmount()).movePointLeft(exponent);
    }

    private WebhookEventType resolveEventType(String stripeEventType) {
        return switch (stripeEventType) {
            case EVENT_PAYMENT_SUCCEEDED -> WebhookEventType.PAYMENT_SUCCESS;
            case EVENT_PAYMENT_FAILED -> WebhookEventType.PAYMENT_FAILURE;
            default -> WebhookEventType.UNKNOWN;
        };
    }
}
