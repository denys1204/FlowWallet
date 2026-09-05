package com.flowwallet.payment.dto;

import com.flowwallet.payment.validation.TopUpAmount;
import com.flowwallet.platform.validation.Iso4217Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request to start a payment. Reaches this service either from a client through the gateway or from
 * Wallet Service on its behalf.
 * <p>
 * {@code walletId} is taken on trust: Payment Service owns no wallet data and cannot confirm the wallet
 * exists or belongs to the caller. Whoever consumes the resulting event is the only component able to
 * check that, and is expected to.
 *
 * @param transactionReference idempotency key; a repeat with the same reference returns the original transaction
 * @param amount               top-up amount in major currency units (e.g. 50.00)
 * @param currency             ISO 4217 code
 * @param walletId             wallet to credit once the payment confirms
 * @param providerName         which payment provider to use, e.g. {@code STRIPE}
 */
public record CreatePaymentIntentRequest(
        @NotBlank(message = "Transaction reference is required")
        @Size(max = 64, message = "Transaction reference must not exceed 64 characters")
        String transactionReference,

        @TopUpAmount
        @NotNull(message = "Amount is required")
        BigDecimal amount,

        @Iso4217Currency
        @NotBlank(message = "Currency is required")
        String currency,

        @NotNull(message = "Wallet ID is required")
        Long walletId,

        @NotBlank(message = "Provider name is required")
        @Size(max = 32, message = "Provider name must not exceed 32 characters")
        String providerName
) {
}
