package com.flowwallet.payment.provider.stripe.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Sets the API key on the SDK's static holder, which {@code PaymentIntent.create} in {@code StripeClient} reads, and
 * reports at startup when webhooks are disabled for want of a signing secret.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StripeConfig {
    private final StripeProperties properties;

    @PostConstruct
    public void initStripe() {
        Stripe.apiKey = properties.getApi().getKey();

        if (!properties.getWebhook().hasSigningSecret()) {
            log.warn(
                    "Stripe webhooks are disabled: STRIPE_WEBHOOK_SECRET is not set to a signing secret (whsec_...), "
                            + "so every webhook is refused with 400 and no payment is credited"
            );
        }
    }
}
