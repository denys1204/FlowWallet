package com.flowwallet.wallet.transfer;

import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.enums.TransactionType;

import java.math.BigDecimal;

/**
 * The sender's receipt for a transfer.
 * <p>
 * A replay must return a byte-identical body, so nothing here varies with time: no timestamp and no movement id.
 * Only {@link #of} builds it, from the sender's {@code TRANSFER_OUT} leg, in memory for the first answer and
 * stored for a replay. Both hold the ledger's scale, so they render alike.
 * See docs/adr/0014-transfers-in-one-local-transaction.md.
 *
 * @param reference    the Idempotency-Key the transfer was made under, lower-cased
 * @param to           the recipient's user id
 * @param amount       how much moved, at the ledger's scale
 * @param currency     the currency of both wallets
 * @param balanceAfter the sender's balance right after this transfer, which on a replay is a past balance
 */
public record TransferResponse(
        String reference,
        String to,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter
) {
    /**
     * @param out      the sender's leg. The recipient's leg is refused, because a receipt built from it would
     *                 show the recipient's balance to the sender.
     * @param currency the currency of both wallets, which a movement does not carry
     */
    public static TransferResponse of(BalanceHistory out, String currency) {
        if (out.getType() != TransactionType.TRANSFER_OUT) {
            throw new IllegalArgumentException(
                    "A transfer receipt is built from the sending leg, got " + out.getType()
            );
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
