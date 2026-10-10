package com.flowwallet.payment.dto;

import org.jspecify.annotations.Nullable;

/**
 * Whether a withdrawal that meets the terms can be paid out now.
 *
 * @param bankAccount       {@code READY} when the caller's bank account takes payouts in the currency, {@code NOT_SET}
 *                          otherwise
 * @param reserveSufficient whether the platform's available balance, less what payouts not yet transferred still owe,
 *                          covers the amount; null when no bank account is ready, because the reserve is not read then
 */
public record PayoutPreflightResponse(
        String bankAccount,
        @Nullable Boolean reserveSufficient
) {
}
