package com.flowwallet.payment.service.provider;

import com.flowwallet.payment.entity.PaymentTransaction;

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
     * Optional method to handle a provider-specific webhook payload if needed in the strategy.
     * Often webhooks are handled directly by a dedicated controller/service, 
     * but putting it in the strategy allows provider-specific signature verification and parsing.
     * @param payload The raw webhook payload
     * @param signature The signature header
     */
    void handleWebhook(String payload, String signature);
}
