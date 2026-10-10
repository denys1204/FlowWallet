package com.flowwallet.contract.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by Payment Service through its outbox when Stripe reports a withdrawal's payout paid. Consumed by Wallet
 * Service, which shows the withdrawal as {@code PAID} and moves no money.
 * <p>
 * Paid is not final: a payout can fail days after it shows paid, so a {@link PayoutFailedEvent} and a
 * {@link PayoutReturnedEvent} can follow for the same reference. Consumers rank the statuses instead of relying on
 * the order of arrival. See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 *
 * @param eventId              identifies this message; stable across redeliveries and topic replays
 * @param schemaVersion        payload version, bumped only if a change cannot be made additively
 * @param transactionReference the withdrawal this event belongs to
 * @param providerPayoutId     Stripe's payout id ({@code po_xxx}); always set on this event, and a consumer refuses
 *                             one without it
 * @param amount               the withdrawal's amount in major currency units
 * @param currency             ISO 4217 code
 * @param userId               whose withdrawal it is; with {@code currency} this identifies the wallet
 * @param paidAt               when Payment Service recorded the payout as paid
 */
public record PayoutPaidEvent(
        String eventId,
        int schemaVersion,
        String transactionReference,
        String providerPayoutId,
        BigDecimal amount,
        String currency,
        String userId,
        Instant paidAt
) {}
