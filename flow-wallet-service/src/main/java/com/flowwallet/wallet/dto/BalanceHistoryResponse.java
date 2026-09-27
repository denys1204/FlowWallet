package com.flowwallet.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement on a wallet. It carries no row id: the id orders nothing and no endpoint accepts it, and beside
 * {@code entryNo} it would invite passing the wrong number as the cursor.
 * See docs/adr/0021-per-wallet-ledger-entry-numbers.md.
 *
 * @param entryNo              the movement's place in its wallet's ledger, counting from 1 in commit order; the
 *                             cursor for paging further back
 * @param transactionReference links the movement to the payment or transfer that caused it; both legs of a
 *                             transfer carry the same one
 * @param type                 the {@code TransactionType} name, which gives both direction and source
 * @param counterpartyUserId   the other user of a transfer: the recipient on {@code TRANSFER_OUT}, the sender
 *                             on {@code TRANSFER_IN}; null for any other movement
 * @param amount               how much moved, always positive
 */
public record BalanceHistoryResponse(
        Long entryNo,
        String transactionReference,
        String type,
        String counterpartyUserId,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        Instant createdAt
) {}
