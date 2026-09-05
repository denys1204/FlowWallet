package com.flowwallet.payment.provider;

import java.util.Map;

import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.dto.WebhookResult;

public interface PaymentProviderStrategy {
    
    /**
     * Checks if this strategy supports the given provider.
     */
    boolean supports(PaymentProvider provider);

    /**
     * Rejects what this provider can refuse without a network call.
     * <p>
     * Separate from {@link #initiatePayment} so the caller can run it before writing a transaction row.
     * A request rejected afterwards leaves its reference taken and the client unable to retry with a
     * corrected amount.
     *
     * @param context the local payment request context
     */
    void validateRequest(PaymentRequestContext context);

    /**
     * Initiates a payment process with the provider.
     * @param context The local payment request context.
     * @return Any provider-specific transaction ID and initialization data (e.g. clientSecret for Stripe).
     */
    PaymentInitiationResult initiatePayment(PaymentRequestContext context);

    /**
     * Parses and verifies a provider-specific webhook payload.
     * Returns a provider-agnostic {@link WebhookResult} so the caller can decide
     * what domain action to perform — keeping the strategy free of domain service dependencies.
     *
     * @param payload The raw webhook payload
     * @param headers The HTTP request headers (can be used to extract signatures, etc.)
     * @return parsed result with provider transaction ID, event ID and classified event type
     */
    WebhookResult handleWebhook(String payload, Map<String, String> headers);
}
