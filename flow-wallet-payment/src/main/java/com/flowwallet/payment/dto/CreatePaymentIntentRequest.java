package com.flowwallet.payment.dto;

import com.flowwallet.payment.validation.DepositAmount;
import com.flowwallet.platform.validation.Iso4217Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request to start a payment, sent by Wallet Service for its caller. It names no wallet: the owner comes from
 * {@code X-User-Id} and the currency from this body. See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 *
 * @param transactionReference the caller's {@code Idempotency-Key}, lower-cased by Wallet Service and bound to
 *                             the terms it is first used with. See docs/adr/0005-client-supplied-idempotency-keys.md.
 *                             The pattern bounds it before any problem detail quotes it. See
 *                             docs/adr/0026-problem-details-never-quote-rejected-input.md.
 * @param amount               deposit amount in major currency units (e.g. 50.00)
 * @param currency             upper-case ISO 4217 code
 * @param providerName         which payment provider to use, e.g. {@code STRIPE}
 */
public record CreatePaymentIntentRequest(
        @NotBlank(message = "Transaction reference is required")
        @Pattern(
                regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                message = "Transaction reference must be a lower-case UUID"
        )
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
