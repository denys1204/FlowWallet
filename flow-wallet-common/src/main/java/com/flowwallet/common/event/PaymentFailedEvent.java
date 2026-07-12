package com.flowwallet.common.event;

import java.time.Instant;

/**
 * Kafka event published by Payment Service (via Transactional Outbox)
 * when a Stripe payment fails.
 *
 * @param transactionReference  unique reference for idempotency
 * @param providerTransactionId provider transaction ID
 * @param walletId              target wallet
 * @param userId                wallet owner's user ID
 * @param reason                reason for failure
 * @param failedAt              when the payment was confirmed as failed
 */
public record PaymentFailedEvent(
        String transactionReference,
        String providerTransactionId,
        Long walletId,
        String userId,
        String reason,
        Instant failedAt
) {}
