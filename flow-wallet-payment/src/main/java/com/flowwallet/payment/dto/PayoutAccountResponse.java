package com.flowwallet.payment.dto;

import org.jspecify.annotations.Nullable;

/**
 * The caller's bank account as Payment Service knows it: whether a payout can go to it, and what identifies it to
 * the user. No bank number crosses the wire, only Stripe's last four digits.
 *
 * @param state    {@code READY} when a payout can go to it now, {@code NOT_SET} otherwise, including when none is set
 * @param last4    the bank account's last four digits; null when none is set
 * @param currency upper-case ISO 4217 code of the bank account; null when none is set
 */
public record PayoutAccountResponse(
        String state,
        @Nullable String last4,
        @Nullable String currency
) {
}
