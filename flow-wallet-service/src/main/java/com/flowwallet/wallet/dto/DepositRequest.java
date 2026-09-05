package com.flowwallet.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Client request to deposit into a wallet. The currency is not carried here — a wallet is denominated in a
 * single currency, and taking one from the caller would only create a way for the two to disagree.
 * <p>
 * The accepted amount range belongs to Payment Service, which enforces it. This request checks only
 * what the wallet itself can know; a copy of the bounds here would be a second source of truth that
 * eventually drifts from the first.
 *
 * @param amount amount in major currency units
 */
public record DepositRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        BigDecimal amount
) {}
