package com.flowwallet.wallet.withdrawal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Client request to withdraw from a wallet to the caller's bank account. The currency is the wallet's own, named by
 * the path. The terms Payment Service sets, the payout currencies, the minimum and the payout grid, are checked there
 * through the preflight, so no bounds are declared here.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 *
 * @param amount amount in major currency units, a decimal string or a JSON number
 */
public record WithdrawalRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        BigDecimal amount
) {
}
