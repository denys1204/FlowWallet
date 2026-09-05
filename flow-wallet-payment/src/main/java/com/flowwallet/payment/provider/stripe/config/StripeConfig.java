package com.flowwallet.payment.provider.stripe.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * Pushes the API key into Stripe's static holder, which is the only way its SDK accepts one.
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
