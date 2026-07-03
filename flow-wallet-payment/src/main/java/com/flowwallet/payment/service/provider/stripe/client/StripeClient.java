package com.flowwallet.payment.service.provider.stripe.client;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.flowwallet.payment.service.provider.stripe.config.StripeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Wrapper for Stripe static methods to allow for easy unit testing via mocking.
 */
@Component
@RequiredArgsConstructor
public class StripeClient {
    private final StripeConfig config;

    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException {
        return PaymentIntent.create(params);
    }
    
    public Event constructEvent(String payload, String signature) throws SignatureVerificationException {
        return Webhook.constructEvent(payload, signature, config.getWebhookSecret(), 300L);
    }
}
