package com.flowwallet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Client request to create a new wallet.
 * <p>
 * {@code userId} is resolved from the {@code @CurrentUserId} header.
 *
 * @param currency ISO 4217 currency code (e.g. "PLN", "EUR", "USD")
 */
public record CreateWalletRequest(
        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO 4217 code")
        String currency
) {}
