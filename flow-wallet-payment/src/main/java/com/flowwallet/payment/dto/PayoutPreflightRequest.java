package com.flowwallet.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Asks whether a withdrawal can go ahead before Wallet Service debits it, for the caller in {@code X-User-Id}.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 *
 * @param currency the withdrawal's currency, which the wallet sends in upper case
 * @param amount   in major units
 */
public record PayoutPreflightRequest(
        @NotNull(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be three capital letters")
        String currency,

        @NotNull(message = "amount is required")
        BigDecimal amount
) {
}
