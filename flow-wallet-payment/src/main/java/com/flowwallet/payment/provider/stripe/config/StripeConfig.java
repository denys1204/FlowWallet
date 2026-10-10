package com.flowwallet.payment.provider.stripe.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Sets the API key on the SDK's static holder, which the {@code PaymentIntent} calls in {@code StripeClient} read, and
 * reports at startup when either webhook endpoint is disabled for want of a signing secret.
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
                            + "so every webhook is refused with 400 and a paid deposit waits for the reconciler"
            );
        }
        if (!properties.getConnectWebhook().hasSigningSecret()) {
            log.warn(
                    "Stripe Connect webhooks are disabled: STRIPE_CONNECT_WEBHOOK_SECRET is not set to a signing "
                            + "secret, so every Connect webhook is refused with 400 and payouts settle at the payout "
                            + "reconciler's pace"
            );
        }
    }
}
