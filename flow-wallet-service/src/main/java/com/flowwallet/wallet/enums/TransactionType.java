package com.flowwallet.wallet.enums;

/**
 * Kind of movement on a wallet.
 * <p>
 * The type carries both the direction and the source of the money, so an amount is always positive and there
 * is no separate source column. The type is what a statement renders and what reports group by; a second
 * column holding half of that meaning is the one the next {@code GROUP BY} forgets.
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
