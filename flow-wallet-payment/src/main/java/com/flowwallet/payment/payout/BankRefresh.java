package com.flowwallet.payment.payout;

/**
 * What re-reading a connected account did to its stored bank account state.
 */
public enum BankRefresh {
    /**
     * No payout account holds the connected account.
     */
    UNKNOWN_ACCOUNT,

    /**
     * The payout account has no bank account yet; the request that sets one records its state.
     */
    NO_BANK_ACCOUNT,

    /**
     * Another writer changed the row while Stripe was read, so neither read is known to be the newer one.
     */
    RACED,

    /**
     * Stripe's default is another bank account than the stored one, which a request in flight records.
     */
    REPLACED,

    CHANGED,

    UNCHANGED
}
