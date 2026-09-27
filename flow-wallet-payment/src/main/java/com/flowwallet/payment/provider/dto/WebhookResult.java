package com.flowwallet.payment.provider.dto;

import com.flowwallet.payment.provider.PaymentProviderStrategy;

import java.math.BigDecimal;

/**
 * Provider-agnostic result of {@link PaymentProviderStrategy#handleWebhook}. Every field is null for
 * {@link WebhookEventType#UNKNOWN}.
 *
 * @param providerTransactionId provider-side transaction ID (e.g. Stripe PaymentIntent ID)
 * @param providerEventId       provider-side event ID, the same on every redelivery, used to skip a processed one
 * @param amount                the payment's amount in major currency units as the provider reports it, compared
 *                              with the stored transaction before a success is applied; null if the event has none
 * @param currency              the payment's ISO 4217 code as the provider reports it; null if the event has none
 */
public record WebhookResult(
        String providerTransactionId,
        String providerEventId,
        WebhookEventType eventType,
        BigDecimal amount,
        String currency
) {
    public static WebhookResult unknown() {
        return new WebhookResult(null, null, WebhookEventType.UNKNOWN, null, null);
    }
}
