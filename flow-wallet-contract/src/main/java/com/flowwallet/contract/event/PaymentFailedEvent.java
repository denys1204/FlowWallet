package com.flowwallet.contract.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by Payment Service through the Transactional Outbox when a payment fails.
 * <p>
 * Failure is not terminal: the same payment may be retried and succeed, producing a
 * {@link PaymentCompletedEvent} for the same {@code transactionReference}.
 * See docs/adr/0009-payment-event-contract.md.
 *
 * @param eventId               identifies this message; stable across redeliveries and topic replays
 * @param schemaVersion         payload version, bumped only if a change cannot be made additively
 * @param transactionReference  the payment this event belongs to
 * @param providerTransactionId provider's own id, carried for support and tracing
 * @param amount                amount in major currency units
 * @param currency              ISO 4217 code
 * @param userId                who paid
 * @param reason                human-readable description of the failure, for logs and support; not a stable
 *                              code and not the provider's own message, so do not branch on it
 * @param failedAt              when the failure was confirmed
 */
public record PaymentFailedEvent(
        String eventId,
        int schemaVersion,
        String transactionReference,
        String providerTransactionId,
        BigDecimal amount,
        String currency,
        String userId,
        String reason,
        Instant failedAt
) {}
