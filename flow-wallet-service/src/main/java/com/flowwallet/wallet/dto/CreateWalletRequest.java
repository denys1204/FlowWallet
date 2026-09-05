package com.flowwallet.wallet.dto;

import com.flowwallet.platform.validation.Iso4217Currency;
import jakarta.validation.constraints.NotBlank;

/**
 * Client request to create a wallet. A user may hold several wallets, but only one per currency.
 * <p>
 * The owner is not part of the body — it is resolved from the request through {@code @CurrentUserId},
 * so a caller cannot create a wallet for somebody else.
 *
 * @param currency ISO 4217 code the wallet is denominated in; fixed for the wallet's lifetime
 */
public record CreateWalletRequest(
        @Iso4217Currency
        @NotBlank(message = "Currency is required")
        String currency
) {
}
