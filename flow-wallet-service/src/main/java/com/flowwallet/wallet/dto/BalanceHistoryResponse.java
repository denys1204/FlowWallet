package com.flowwallet.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement on a wallet.
 *
 * @param id                   the movement's id, and the cursor for paging further back
 * @param transactionReference links the movement to the payment or transfer that caused it; both legs of a
 *                             transfer carry the same one
 * @param type                 what kind of movement this was
 * @param counterpartyUserId   the other user of a transfer: the recipient on {@code TRANSFER_OUT}, the sender
 *                             on {@code TRANSFER_IN}; null for any other movement
 * @param amount               how much moved
 * @param balanceBefore        balance before the movement
 * @param balanceAfter         balance after it
 * @param createdAt            when it was recorded
 */
public record BalanceHistoryResponse(
        Long id,
        String transactionReference,
        String type,
        String counterpartyUserId,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        Instant createdAt
) {}
