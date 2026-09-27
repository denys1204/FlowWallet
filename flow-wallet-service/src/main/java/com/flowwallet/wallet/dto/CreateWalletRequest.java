package com.flowwallet.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Client request to create a wallet. The owner comes from {@code @CurrentUserId}, never from the body.
 * <p>
 * The currency carries no {@code @Iso4217Currency}: it is case-sensitive and would refuse {@code "usd"}, which
 * the path accepts. The pattern admits three ASCII letters in either case and nothing else, so a value with line
 * breaks or of any length is refused here without being quoted; the service normalises and validates the code as
 * it does the path's. See docs/adr/0015-currency-precision-and-no-rounding.md and
 * docs/adr/0026-problem-details-never-quote-rejected-input.md.
 *
 * @param currency ISO 4217 code the wallet is denominated in, either case; fixed for the wallet's lifetime
 */
public record CreateWalletRequest(
        @NotNull(message = "Currency is required")
        @Pattern(regexp = "[A-Za-z]{3}", message = "Currency must be a three-letter ISO 4217 code")
        String currency
) {
}
