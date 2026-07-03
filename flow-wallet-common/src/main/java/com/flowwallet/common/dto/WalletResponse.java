package com.flowwallet.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response representing a wallet's current state.
 *
 * @param id        wallet ID
 * @param userId    wallet owner's user ID
 * @param balance   current balance
 * @param currency  ISO 4217 currency code
 * @param createdAt when the wallet was created
 * @param updatedAt when the wallet was last modified
 */
public record WalletResponse(
        Long id,
        String userId,
        BigDecimal balance,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
}
