package com.flowwallet.wallet.bankaccount;

import org.jspecify.annotations.Nullable;

/**
 * The caller's bank account as Payment Service reports it. Nothing about it is stored in the wallet.
 *
 * @param state    {@code READY} when a withdrawal can be paid out to it now, {@code NOT_SET} otherwise
 * @param last4    the bank account's last four digits; null when none is set
 * @param currency upper-case ISO 4217 code of the bank account; null when none is set
 */
public record BankAccountResponse(
        String state,
        @Nullable String last4,
        @Nullable String currency
) {
}
