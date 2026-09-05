package com.flowwallet.wallet.enums;

/**
 * Why an event was refused. Every name fits the {@code VARCHAR(20)} column that stores it.
 * <p>
 * The list is short because the destination cannot be wrong: a wallet is found by the owner and currency
 * the event itself carries, so there is no unknown wallet, no foreign owner and no currency mismatch to
 * reject. What remains is an event that does not describe a payment we can act on.
 */
public enum RejectionReason {

    /** Amount absent, zero or negative — crediting it would debit the wallet. */
    INVALID_AMOUNT,

    /** A field the credit depends on is missing: transaction reference, currency or user. */
    INVALID_ENVELOPE,

    /**
     * Two different events claim one transaction reference. One of them is a real payment that will not be
     * credited, so this is a producer contract violation rather than a routine redelivery.
     */
    DUPLICATE_REFERENCE
}
