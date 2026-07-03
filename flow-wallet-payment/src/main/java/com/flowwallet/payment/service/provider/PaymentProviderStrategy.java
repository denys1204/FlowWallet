package com.flowwallet.payment.service.provider;

import com.flowwallet.payment.entity.PaymentTransaction;

import java.util.Map;

public interface PaymentProviderStrategy {
    
    /**
     * Checks if this strategy supports the given provider.
     */
    boolean supports(PaymentProvider provider);

    /**
     * Initiates a payment process with the provider.
     * @param transaction The local transaction record.
     * @return Any provider-specific client secret or initialization data as a String (e.g. clientSecret for Stripe).
     */
    String initiatePayment(PaymentTransaction transaction);

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
