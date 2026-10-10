package com.flowwallet.payment.payout;

/**
 * A row the payout reconciler may ask Stripe about. It carries no user id, so it is safe to log.
 */
public record ReconcilablePayout(
        Long id,
        String reference,
        PayoutStatus status,
        String stripeAccountId,
        String stripePayoutId
) {
}
