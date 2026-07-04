package com.flowwallet.payment.provider.stripe.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;

@Configuration
public class StripeConfig {
    @Getter
    private final String webhookSecret;
    private final String apiKey;

    public StripeConfig(
            @Value("${stripe.api.key}") String apiKey,
            @Value("${stripe.webhook.secret}") String webhookSecret
    ) {
        this.apiKey = apiKey;
        this.webhookSecret = webhookSecret;
    }

    @PostConstruct
    public void initStripe() {
        Stripe.apiKey = this.apiKey;
    }
}
