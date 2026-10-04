package com.flowwallet.payment.provider.dto;

import com.flowwallet.payment.provider.PaymentProviderStrategy;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Provider-agnostic result of {@link PaymentProviderStrategy#handleWebhook} and of
 * {@link PaymentProviderStrategy#checkPayment}. Every field is null for {@link WebhookEventType#UNKNOWN}.
 *
 * @param providerTransactionId provider-side transaction ID (e.g. Stripe PaymentIntent ID)
 * @param providerEventId       provider-side event ID, the same on every redelivery, used to skip a processed one;
 *                              a reconciled result carries one built from the payment's ID
 * @param amount                the payment's amount in major currency units as the provider reports it, compared
 *                              with the stored transaction before a success is applied; null if the event has none
 * @param currency              the payment's ISO 4217 code as the provider reports it; null if the event has none
 * @param occurredAt            when the provider settled the payment, carried into the published event; for a
 *                              reconciled success, when the reconciler saw it, since a PaymentIntent records no
 *                              time of success
 */
public record WebhookResult(
        String providerTransactionId,
        String providerEventId,
        WebhookEventType eventType,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
    /**
     * Prefix of the event id a reconciled result carries, followed by the provider's payment id. Stripe's own event ids
     * start with {@code evt_}, so the two cannot collide in the unique {@code provider_event_id} column, and the
     * reconciler's scan recognises its own failures by it.
     * See docs/adr/0032-pending-payments-are-rechecked-with-the-provider.md.
     */
    public static final String RECONCILED_EVENT_PREFIX = "reconcile:";

    public static WebhookResult unknown() {
        return new WebhookResult(null, null, WebhookEventType.UNKNOWN, null, null, null);
    }
}
