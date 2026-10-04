package com.flowwallet.wallet.dto;

import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.balance.BalanceHistory;

import java.time.Instant;

/**
 * One movement on a wallet. It carries no row id: the id orders nothing and no endpoint accepts it, and beside
 * {@code entryNo} it would invite passing the wrong number as the cursor.
 * See docs/adr/0021-per-wallet-ledger-entry-numbers.md.
 * <p>
 * The three amounts are decimal strings at the wallet currency's scale.
 * See docs/adr/0030-amounts-in-responses-are-decimal-strings.md.
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
        String amount,
        String balanceBefore,
        String balanceAfter,
        Instant createdAt
) {
    /**
     * @param currency the currency of the wallet the movement belongs to, which a movement does not carry
     */
    public static BalanceHistoryResponse of(BalanceHistory movement, String currency) {
        return new BalanceHistoryResponse(
                movement.getEntryNo(),
                movement.getTransactionReference(),
                movement.getType().name(),
                movement.getCounterpartyUserId(),
                AmountPrecision.render(movement.getAmount(), currency),
                AmountPrecision.render(movement.getBalanceBefore(), currency),
                AmountPrecision.render(movement.getBalanceAfter(), currency),
                movement.getCreatedAt()
        );
    }
}
