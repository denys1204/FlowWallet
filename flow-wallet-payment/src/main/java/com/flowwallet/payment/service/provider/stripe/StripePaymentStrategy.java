package com.flowwallet.payment.service.provider.stripe;

import com.flowwallet.payment.entity.PaymentTransaction;
import com.flowwallet.payment.service.provider.PaymentProvider;
import com.flowwallet.payment.service.provider.PaymentProviderStrategy;
import com.flowwallet.payment.service.provider.stripe.client.StripeClient;
import com.flowwallet.payment.service.provider.stripe.mapper.StripeRequestMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
            throw new RuntimeException("Stripe payment initiation failed", e);
        }
    }

    @Override
    public void handleWebhook(String payload, String signature) {
        // Will be implemented in Phase 2.5 (Webhook processing)
        log.info("Received Stripe webhook. Signature: {}", signature);
    }
}
