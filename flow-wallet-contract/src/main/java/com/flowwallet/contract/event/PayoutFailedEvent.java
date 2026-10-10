package com.flowwallet.contract.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by Payment Service through its outbox once a withdrawal's money is certain to come back: the payout
 * failed or was canceled, or it could not be made, and the transfer is being reversed. Consumed by Wallet Service,
 * which shows the withdrawal as {@code RETURN_PENDING} and moves no money; the credit waits for
 * {@link PayoutReturnedEvent}.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 *
 * @param eventId              identifies this message; stable across redeliveries and topic replays
 * @param schemaVersion        payload version, bumped only if a change cannot be made additively
 * @param transactionReference the withdrawal this event belongs to
 * @param providerPayoutId     Stripe's payout id ({@code po_xxx}); null when no payout was created
 * @param amount               the withdrawal's amount in major currency units
 * @param currency             ISO 4217 code
 * @param userId               whose withdrawal it is
 * @param reasonCode           why the money comes back, from an open set of strings such as {@code PAYOUT_FAILED};
 *                             a consumer stores it and never branches on it
 * @param providerFailureCode  Stripe's failure code for the payout, such as {@code no_account}; null when Stripe gave
 *                             none
 * @param failedAt             when Payment Service recorded the failure
 */
public record PayoutFailedEvent(
        String eventId,
        int schemaVersion,
        String transactionReference,
        String providerPayoutId,
        BigDecimal amount,
        String currency,
        String userId,
        String reasonCode,
        String providerFailureCode,
        Instant failedAt
) {}
