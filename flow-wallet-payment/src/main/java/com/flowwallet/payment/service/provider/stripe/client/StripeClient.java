package com.flowwallet.payment.service.provider.stripe.client;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Component;

/**
 * Wrapper for Stripe static methods to allow for easy unit testing via mocking.
 */
@Component
public class StripeClient {
    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException {
        return PaymentIntent.create(params);
    }
}
