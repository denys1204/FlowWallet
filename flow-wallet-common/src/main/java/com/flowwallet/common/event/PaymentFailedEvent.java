package com.flowwallet.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka event published by Payment Service (via Transactional Outbox)
 * when a Stripe payment fails.
 *
 * @param transactionReference  unique reference for idempotency
 * @param providerTransactionId provider transaction ID
 * @param amount                payment amount in major currency units
 * @param currency              ISO 4217 currency code
 * @param walletId              target wallet
 * @param userId                wallet owner's user ID
 * @param reason                reason for failure
 * @param failedAt              when the payment was confirmed as failed
 */
public record PaymentFailedEvent(
        String transactionReference,
        String providerTransactionId,
        BigDecimal amount,
        String currency,
        Long walletId,
        String userId,
        String reason,
        Instant failedAt
) {}
