package com.flowwallet.payment.payout;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the operator's code reads of a payout, without its user id, so it is safe to log and to answer with.
 *
 * @param updatedAt the hand-over to an operator while the row is in {@code NEEDS_ATTENTION}: nothing writes a waiting
 *                  row until an operator moves it, and a writer that did would only start the quiet period again
 */
record PayoutSnapshot(
        Long id,
        Long version,
        String reference,
        PayoutStatus status,
        @Nullable PayoutStatus attentionFrom,
        @Nullable AttentionCause attentionCause,
        @Nullable String attentionDetail,
        BigDecimal amount,
        String currency,
        @Nullable PayoutReason reasonCode,
        @Nullable String failureCode,
        int keyGen,
        int attempts,
        Instant nextAttemptAt,
        @Nullable String stripeAccountId,
        @Nullable String stripeTransferId,
        @Nullable String stripePayoutId,
        @Nullable String stripeReversalId,
        @Nullable Instant transferredAt,
        @Nullable Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {
    static PayoutSnapshot of(Payout payout) {
        return new PayoutSnapshot(
                payout.getId(),
                payout.getVersion(),
                payout.getTransactionReference(),
                payout.getStatus(),
                payout.getAttentionFrom(),
                payout.getAttentionCause(),
                payout.getAttentionDetail(),
                payout.getAmount(),
                payout.getCurrency(),
                payout.getReasonCode(),
                payout.getFailureCode(),
                payout.getKeyGen(),
                payout.getAttempts(),
                payout.getNextAttemptAt(),
                payout.getStripeAccountId(),
                payout.getStripeTransferId(),
                payout.getStripePayoutId(),
                payout.getStripeReversalId(),
                payout.getTransferredAt(),
                payout.getPaidAt(),
                payout.getCreatedAt(),
                payout.getUpdatedAt()
        );
    }
}
