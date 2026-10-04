package com.flowwallet.contract.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by Payment Service through the Transactional Outbox once a payment is confirmed.
 * Consumed by Wallet Service to credit the balance.
 * <p>
 * A failed payment can be retried and succeed, so one {@code transactionReference} may carry a
 * {@link PaymentFailedEvent} and then this event. Consumers deduplicate on {@code eventId}, never on
 * {@code transactionReference}. See docs/adr/0009-payment-event-contract.md.
 *
 * @param eventId               identifies this message; stable across redeliveries and topic replays
 * @param schemaVersion         payload version, bumped only if a change cannot be made additively
 * @param transactionReference  the payment this event belongs to; also the credit barrier for consumers
 * @param providerTransactionId provider's own id (Stripe {@code pi_xxx}), carried for support and tracing
 * @param amount                amount in major currency units
 * @param currency              ISO 4217 code
 * @param userId                who paid; with {@code currency} this identifies the wallet to credit
 * @param completedAt           when the provider confirmed the payment; for a payment the reconciler settled, when
 *                              the reconciler saw it succeed
 */
public record PaymentCompletedEvent(
        String eventId,
        int schemaVersion,
        String transactionReference,
        String providerTransactionId,
        BigDecimal amount,
        String currency,
        String userId,
        Instant completedAt
) {}
