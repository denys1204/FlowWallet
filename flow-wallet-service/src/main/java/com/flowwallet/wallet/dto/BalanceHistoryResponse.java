package com.flowwallet.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement on a wallet.
 *
 * @param id                   the movement's id, and the cursor for paging further back
 * @param transactionReference links the movement to the payment that caused it
 * @param type                 what kind of movement this was
 * @param amount               how much moved
 * @param balanceBefore        balance before the movement
 * @param balanceAfter         balance after it
 * @param createdAt            when it was recorded
 */
public record BalanceHistoryResponse(
        Long id,
        String transactionReference,
        String type,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        Instant createdAt
) {}
