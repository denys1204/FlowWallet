package com.flowwallet.wallet.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Client request to create a wallet. A user may hold several wallets, but only one per currency.
 * <p>
 * The owner is not part of the body — it is resolved from the request through {@code @CurrentUserId},
 * so a caller cannot create a wallet for somebody else.
 *
 * <p>
 * The currency is not validated here with {@code @Iso4217Currency}, deliberately: that constraint is
 * case-sensitive, so {@code "usd"} would be refused in the body while {@code /api/wallets/usd} is accepted
 * in the path. The service normalises and validates both the same way.
 *
 * @param currency ISO 4217 code the wallet is denominated in, either case; fixed for the wallet's lifetime
 */
public record CreateWalletRequest(
        @NotBlank(message = "Currency is required")
        String currency
) {
}
