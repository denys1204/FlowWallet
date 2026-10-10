package com.flowwallet.contract.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by Payment Service through its outbox once a withdrawal's amount is to be credited back: the Stripe
 * transfer was reversed, no transfer was made, or an operator returned the payout. Consumed by Wallet Service,
 * which credits the withdrawal's amount back once under the reference.
 * <p>
 * The only payout event that moves money, which is why the order of payout events does not matter. A second return
 * for one reference under another {@code eventId} is a producer defect.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 *
 * @param eventId              identifies this message; stable across redeliveries and topic replays
 * @param schemaVersion        payload version, bumped only if a change cannot be made additively
 * @param transactionReference the withdrawal this event belongs to; also the credit barrier for consumers
 * @param providerPayoutId     Stripe's payout id ({@code po_xxx}); null when no payout was created
 * @param amount               the withdrawal's amount in major currency units, credited back in full
 * @param currency             ISO 4217 code
 * @param userId               whose withdrawal it is; with {@code currency} this identifies the wallet to credit
 * @param reasonCode           why the money came back, from an open set of strings such as
 *                             {@code PLATFORM_FUNDS_SHORT}; a consumer stores it and never branches on it
 * @param returnedAt           when Payment Service recorded the return
 */
public record PayoutReturnedEvent(
        String eventId,
        int schemaVersion,
        String transactionReference,
        String providerPayoutId,
        BigDecimal amount,
        String currency,
        String userId,
        String reasonCode,
        Instant returnedAt
) {}
