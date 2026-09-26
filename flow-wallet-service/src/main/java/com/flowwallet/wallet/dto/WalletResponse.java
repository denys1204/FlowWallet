package com.flowwallet.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A wallet's current state. It carries no wallet id, which no URL accepts, and no owner, who is the caller.
 * See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 *
 * @param currency  upper-case ISO 4217 code the wallet is denominated in
 * @param updatedAt when its balance last changed
 */
public record WalletResponse(
        BigDecimal balance,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {}
