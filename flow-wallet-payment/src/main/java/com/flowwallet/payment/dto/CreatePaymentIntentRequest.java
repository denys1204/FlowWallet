package com.flowwallet.payment.dto;

import com.flowwallet.payment.validation.DepositAmount;
import com.flowwallet.platform.validation.Iso4217Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request to start a payment. Reaches this service either from a client through the gateway or from
 * Wallet Service on its behalf.
 * <p>
 * The destination is not named here. A wallet is identified by its owner and its currency, and both are
 * already present — the owner from the authenticated caller, the currency below — so asking for a wallet id
 * as well would add a second name for the same thing that this service cannot check against the first.
 *
 * @param transactionReference idempotency key; a repeat with the same reference returns the original transaction
 * @param amount               deposit amount in major currency units (e.g. 50.00)
 * @param currency             ISO 4217 code
 * @param providerName         which payment provider to use, e.g. {@code STRIPE}
 */
public record CreatePaymentIntentRequest(
        @NotBlank(message = "Transaction reference is required")
        @Size(max = 64, message = "Transaction reference must not exceed 64 characters")
        String transactionReference,

        @DepositAmount
        @NotNull(message = "Amount is required")
        BigDecimal amount,

        @Iso4217Currency
        @NotBlank(message = "Currency is required")
        String currency,

        @NotBlank(message = "Provider name is required")
        @Size(max = 32, message = "Provider name must not exceed 32 characters")
        String providerName
) {
}
