package com.flowwallet.wallet.deposit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Client request to deposit into a wallet. The currency is the wallet's own, named by the path.
 * <p>
 * The accepted amount range belongs to Payment Service, the only place that enforces it, so no bounds are
 * declared here. See docs/adr/0013-deposit-initiation.md.
 *
 * @param amount amount in major currency units
 */
public record DepositRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        BigDecimal amount
) {}
