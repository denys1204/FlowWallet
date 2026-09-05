package com.flowwallet.contract.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by Payment Service through the Transactional Outbox when a payment fails.
 * <p>
 * Failure is not terminal: the same provider payment may be retried and later succeed, producing a
 * {@link PaymentCompletedEvent} for the same {@code transactionReference}. Consumers must not treat
 * this event as the end of the story.
 *
 * @param eventId               identifies this message; stable across redeliveries and topic replays
 * @param schemaVersion         payload version, bumped only if a change cannot be made additively
 * @param transactionReference  the payment this event belongs to
 * @param providerTransactionId provider's own id, carried for support and tracing
 * @param amount                amount in major currency units
 * @param currency              ISO 4217 code
 * @param walletId              wallet the payment was aimed at
 * @param userId                who paid
 * @param reason                free-form provider message; not a stable code, do not branch on it
 * @param failedAt              when the failure was confirmed
 */
public record PaymentFailedEvent(
        String eventId,
        int schemaVersion,
        String transactionReference,
        String providerTransactionId,
        BigDecimal amount,
        String currency,
        Long walletId,
        String userId,
        String reason,
        Instant failedAt
) {}
