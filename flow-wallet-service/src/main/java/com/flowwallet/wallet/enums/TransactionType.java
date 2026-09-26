package com.flowwallet.wallet.enums;

/**
 * Kind of movement on a wallet. The type carries both the direction and the source of the money, so an amount
 * is always positive and there is no separate source column.
 * <p>
 * A new type must fit the {@code VARCHAR(20)} column, and one with a counterparty must be added to the
 * {@code balance_history_counterparty_on_transfers} CHECK. See docs/adr/0012-balances-and-append-only-ledger.md.
 */
public enum TransactionType {
    /**
     * Money that came in from outside the system: a card payment through the provider.
     */
    DEPOSIT,
    /**
     * Money that came in from another user's wallet.
     */
    TRANSFER_IN,
    /**
     * Money that went out to another user's wallet.
     */
    TRANSFER_OUT,
    /**
     * Money that goes out of the system: a payout to a card or bank account.
     */
    WITHDRAWAL
}
