package com.flowwallet.wallet.dto;

import com.flowwallet.platform.security.CurrentUserIdResolver;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Client request to move money from the caller's wallet to another user's wallet in the same currency. The path
 * names the caller's wallet, whose currency covers both sides, so the body carries none.
 * <p>
 * {@code to} uses {@code CurrentUserIdResolver}'s own expression, so the recipient follows the caller's identity
 * rule. {@code @UUID} cannot replace it: it answers a fifth dash with a 500 and accepts non-ASCII digits. Unlike
 * the header, surrounding whitespace is refused rather than stripped.
 * See docs/adr/0003-caller-identity-and-trust-boundary.md.
 * <p>
 * The amount's precision depends on the currency, which only the path carries, so the service checks it with
 * {@code AmountPrecision}. See docs/adr/0015-currency-precision-and-no-rounding.md.
 *
 * @param to     the recipient's user id
 * @param amount amount in major currency units
 */
public record TransferRequest(
        @NotNull(message = "Recipient is required")
        @Pattern(
                regexp = CurrentUserIdResolver.RANDOM_UUID_REGEX,
                message = "Recipient must be a user id: a version 4 or 7 UUID"
        )
        String to,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        BigDecimal amount
) {}
