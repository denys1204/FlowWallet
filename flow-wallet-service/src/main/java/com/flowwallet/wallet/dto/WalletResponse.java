package com.flowwallet.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A wallet's current state.
 * <p>
 * Carries neither an id nor an owner. No URL accepts a wallet id — a wallet is addressed by its currency,
 * because the owner is already authenticated — so publishing one would invite clients to build paths that do
 * not exist. The owner is the caller, who sent it in the first place.
 *
 * @param balance   current balance
 * @param currency  ISO 4217 code the wallet is denominated in
 * @param createdAt when the wallet was opened
 * @param updatedAt when its balance last changed
 */
public record WalletResponse(
        BigDecimal balance,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {}
