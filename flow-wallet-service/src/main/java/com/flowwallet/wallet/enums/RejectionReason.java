package com.flowwallet.wallet.enums;

/**
 * Why an event was refused. Every name fits the {@code VARCHAR(20)} column that stores it.
 * <p>
 * The list is short because the destination cannot be misaddressed: a wallet is found by the owner and
 * currency the event itself carries, so there is no foreign owner and no currency mismatch to reject. What
 * remains is an event that does not describe a payment we can act on, or one whose wallet is missing.
 */
public enum RejectionReason {

    /** Amount absent, zero or negative — crediting it would debit the wallet. */
    INVALID_AMOUNT,

    /** A field the credit depends on is missing: transaction reference, currency or user. */
    INVALID_ENVELOPE,

    /**
     * The user holds no wallet in the event's currency. Wallets are never opened by an event — a deposit is
     * initiated through the wallet, which refuses before any money moves — so this means a payment got in by
     * some other route. The payload is kept and the event can be replayed once the wallet exists.
     */
    WALLET_NOT_FOUND,

    /**
     * Two different events claim one transaction reference. One of them is a real payment that will not be
     * credited, so this is a producer contract violation rather than a routine redelivery.
     */
    DUPLICATE_REFERENCE
}
