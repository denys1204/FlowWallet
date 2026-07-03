package com.flowwallet.payment.service.provider;

/**
 * Provider-agnostic result of webhook parsing.
 * Returned by {@link PaymentProviderStrategy#handleWebhook} so the strategy
 * never needs to know about domain services — breaking the circular dependency.
 *
 * @param providerTransactionId provider-side transaction ID (e.g. Stripe PaymentIntent ID)
 * @param providerEventId       provider-side event ID for idempotency
 * @param eventType             classified event type
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
