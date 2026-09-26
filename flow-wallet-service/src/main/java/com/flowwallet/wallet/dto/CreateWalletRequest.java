package com.flowwallet.wallet.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Client request to create a wallet. The owner comes from {@code @CurrentUserId}, never from the body.
 * <p>
 * The currency carries no {@code @Iso4217Currency}: it is case-sensitive and would refuse {@code "usd"}, which
 * the path accepts. The service normalises and validates both the same way.
 * See docs/adr/0015-currency-precision-and-no-rounding.md.
 *
 * @param currency ISO 4217 code the wallet is denominated in, either case; fixed for the wallet's lifetime
 */
public record CreateWalletRequest(
        @NotBlank(message = "Currency is required")
        String currency
) {
}
