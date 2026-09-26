package com.flowwallet.wallet.transfer;

import java.math.BigDecimal;

/**
 * A transfer as {@link TransferHandler} receives it, with every value already in the one form it is locked,
 * stored and compared in. Only {@link TransferService} normalises, and the transaction never does it again.
 * See docs/adr/0014-transfers-in-one-local-transaction.md.
 *
 * @param senderUserId    the caller, lower-cased by {@code CurrentUserIdResolver}
 * @param recipientUserId the recipient, lower-cased, and never the sender
 * @param currency        the upper-cased ISO code from the path, which applies to both wallets
 * @param reference       the lower-cased Idempotency-Key, stored on both legs
 * @param amount          the amount on its currency's grid, at the ledger's scale of 4
 */
record TransferCommand(
        String senderUserId,
        String recipientUserId,
        String currency,
        String reference,
        BigDecimal amount
) {}
