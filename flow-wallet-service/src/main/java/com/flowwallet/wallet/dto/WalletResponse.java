package com.flowwallet.wallet.dto;

import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.balance.Wallet;

import java.time.Instant;

/**
 * A wallet's current state. It carries no wallet id, which no URL accepts, and no owner, who is the caller.
 * See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 * <p>
 * The balance is a decimal string at the currency's scale, and {@code decimals} says what that scale is.
 * See docs/adr/0030-amounts-in-responses-are-decimal-strings.md.
 *
 * @param balance   such as {@code "75.00"} for USD or {@code "1000"} for JPY
 * @param currency  upper-case ISO 4217 code the wallet is denominated in
 * @param decimals  how many decimals the currency's amounts carry, so a client can format and check an amount
 *                  without a currency table of its own
 * @param updatedAt when its balance last changed
 */
public record WalletResponse(
        String balance,
        String currency,
        int decimals,
        Instant createdAt,
        Instant updatedAt
) {
    public static WalletResponse of(Wallet wallet) {
        return new WalletResponse(
                AmountPrecision.render(wallet.getBalance(), wallet.getCurrency()),
                wallet.getCurrency(),
                AmountPrecision.decimals(wallet.getCurrency()),
                wallet.getCreatedAt(),
                wallet.getUpdatedAt()
        );
    }
}
