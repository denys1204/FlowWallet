package com.flowwallet.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka event published by Payment Service (via Transactional Outbox)
 * when a Stripe payment is successfully completed.
 * <p>
 * Consumed by Wallet Service to credit the user's balance.
 *
 * @param transactionReference unique reference for idempotency
 * @param paymentIntentId      Stripe PaymentIntent ID (pi_xxx)
 * @param amount               payment amount in major currency units
 * @param currency             ISO 4217 currency code
 * @param walletId             target wallet to credit
 * @param userId               wallet owner's user ID
 * @param completedAt          when the payment was confirmed by Stripe
 */
public record PaymentCompletedEvent(
        String transactionReference,
        String paymentIntentId,
        BigDecimal amount,
        String currency,
        Long walletId,
        String userId,
        Instant completedAt
) {
}
