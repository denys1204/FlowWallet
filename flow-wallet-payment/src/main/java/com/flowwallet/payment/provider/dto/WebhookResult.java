package com.flowwallet.payment.provider.dto;

import com.flowwallet.payment.provider.PaymentProviderStrategy;

/**
 * Provider-agnostic result of {@link PaymentProviderStrategy#handleWebhook}. Both ids are null for
 * {@link WebhookEventType#UNKNOWN}.
 *
 * @param providerTransactionId provider-side transaction ID (e.g. Stripe PaymentIntent ID)
 * @param providerEventId       provider-side event ID, the same on every redelivery, used to skip a processed one
 */
public record WebhookResult(
        String providerTransactionId,
        String providerEventId,
        WebhookEventType eventType
) {
    public static WebhookResult unknown() {
        return new WebhookResult(null, null, WebhookEventType.UNKNOWN);
    }
}
