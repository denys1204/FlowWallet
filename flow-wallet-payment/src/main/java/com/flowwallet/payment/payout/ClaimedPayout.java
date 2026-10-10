package com.flowwallet.payment.payout;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * What a claimed step needs, read in the claim's transaction. It carries no user id, so it and every outcome built
 * from it are safe to log. See docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md.
 *
 * @param step           the status the claim took the payout in
 * @param earlierAttempt whether a claim before this one marked the step attempted, so Stripe may hold its object
 * @param claimedUntil   the claim's token: the outcome is recorded only while the row still holds it
 * @param bank           the user's payout account as read at the claim; null when the user has none
 */
public record ClaimedPayout(
        Long id,
        String reference,
        PayoutStatus step,
        BigDecimal amount,
        String currency,
        @Nullable String stripeAccountId,
        @Nullable String stripeTransferId,
        @Nullable Instant transferredAt,
        int keyGen,
        int attempts,
        boolean earlierAttempt,
        Instant claimedUntil,
        @Nullable Bank bank
) {
    /**
     * @param ready whether payouts in the payout's currency can go to the bank account now, on the connected account
     *              the payout's transfer used if it made one
     */
    public record Bank(
            Long accountId,
            @Nullable String stripeAccountId,
            @Nullable String externalAccountId,
            boolean ready
    ) {
    }

    static ClaimedPayout of(Payout payout, Instant claimedAt, Optional<PayoutAccount> account) {
        Bank bank = account.map(found -> new Bank(
                found.getId(),
                found.getStripeAccountId(),
                found.getExternalAccountId(),
                found.isReadyFor(payout.getCurrency())
                        && (payout.getStripeAccountId() == null
                        || payout.getStripeAccountId().equals(found.getStripeAccountId()))
        )).orElse(null);
        return new ClaimedPayout(
                payout.getId(),
                payout.getTransactionReference(),
                payout.getStatus(),
                payout.getAmount(),
                payout.getCurrency(),
                payout.getStripeAccountId(),
                payout.getStripeTransferId(),
                payout.getTransferredAt(),
                payout.getKeyGen(),
                payout.getAttempts(),
                payout.attemptedBefore(claimedAt),
                payout.getClaimedUntil(),
                bank
        );
    }

    public boolean bankReady() {
        return bank != null && bank.ready();
    }
}
