package com.flowwallet.wallet.enums;

/**
 * Why an event was refused. Every name fits the {@code VARCHAR(20)} column that stores it.
 * <p>
 * There is no foreign-owner or currency-mismatch reason, because the wallet is found by the owner and currency
 * the event itself carries. See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 */
public enum RejectionReason {

    /**
     * Amount absent, zero or negative.
     */
    INVALID_AMOUNT,

    /**
     * A field the credit depends on is missing: transaction reference, currency or user.
     */
    INVALID_ENVELOPE,

    /**
     * The user holds no wallet in the event's currency. An event never opens a wallet, so the payment got in by
     * some other route; the event can be replayed from its payload once the wallet exists.
     */
    WALLET_NOT_FOUND,

    /**
     * A different event already credited this transaction reference: a producer contract violation, not a
     * redelivery.
     */
    DUPLICATE_REFERENCE
}
