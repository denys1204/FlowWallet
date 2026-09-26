package com.flowwallet.wallet.transfer;

import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.enums.TransactionType;

import java.math.BigDecimal;

/**
 * The sender's receipt for a transfer.
 * <p>
 * A repeat of the same Idempotency-Key must return a byte-identical body, so nothing here varies with time: no
 * timestamp and no movement id, following {@code DepositResponse}. The movement itself, with both, is in the
 * wallet's history. A {@code createdAt} would also have to survive the trip through the database's timestamp
 * column unchanged for a replay to match the first answer, which is one more way for the two to differ.
 * <p>
 * Only {@link #of} builds it, from the sender's {@code TRANSFER_OUT} leg: the one in memory for the first
 * answer, the stored one for a replay. The two hold the same values at the same scale, because the amount is
 * brought to the ledger's scale before it is written and the balance comes from a {@code NUMERIC(19,4)}
 * column, so they render alike. A second way to build it, say from the command and the wallet, would be a
 * second place for the scale to drift.
 * <p>
 * Nothing about the recipient's wallet appears. {@code balanceAfter} is the sender's balance right after this
 * transfer, which on a replay is history rather than the balance now.
 *
 * @param reference    the Idempotency-Key the transfer was made under, lower-cased
 * @param to           the recipient's user id
 * @param amount       how much moved, at the ledger's scale
 * @param currency     the currency of both wallets
 * @param balanceAfter the sender's balance right after this transfer
 */
public record TransferResponse(
        String reference,
        String to,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter
) {
    /**
     * @param out      the sender's leg. The recipient's leg is refused: its balance belongs to the recipient,
     *                 and a receipt built from it would show that balance to the sender.
     * @param currency the currency of both wallets, which a movement does not carry
     */
    public static TransferResponse of(BalanceHistory out, String currency) {
        if (out.getType() != TransactionType.TRANSFER_OUT) {
            throw new IllegalArgumentException("A transfer receipt is built from the sending leg, got "
                    + out.getType());
        }
        return new TransferResponse(
                out.getTransactionReference(),
                out.getCounterpartyUserId(),
                out.getAmount(),
                currency,
                out.getBalanceAfter()
        );
    }
}
