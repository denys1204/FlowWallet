package com.flowwallet.payment.provider.stripe.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * Sets the API key on the SDK's static holder, which {@code PaymentIntent.create} in {@code StripeClient} reads.
 */
@Configuration
@RequiredArgsConstructor
public class StripeConfig {
    private final StripeProperties properties;

    @PostConstruct
    public void initStripe() {
        Stripe.apiKey = properties.getApi().getKey();
    }
}
