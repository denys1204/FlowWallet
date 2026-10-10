package com.flowwallet.wallet.enums;

/**
 * Why an event was refused. Every name fits the {@code VARCHAR(20)} column that stores it.
 * <p>
 * A payment has no foreign-owner or currency-mismatch reason, because the wallet is found by the owner and currency
 * the event itself carries. A payout event that names another wallet than its withdrawal's is
 * {@code WITHDRAWAL_MISMATCH}. See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 */
public enum RejectionReason {
    /**
     * Amount absent, zero, negative, or off its currency's grid (finer than the currency allows, or more than 15
     * integer digits).
     */
    INVALID_AMOUNT,

    /**
     * A field the event depends on is missing: transaction reference, currency or user, or the payout id of a Paid
     * event.
     */
    INVALID_ENVELOPE,

    /**
     * The user holds no wallet in the event's currency. An event never opens a wallet, so the payment got in by
     * some other route. Once the wallet exists, the payload can be republished under a fresh event id; the
     * original id already has this row. See docs/adr/0010-idempotent-payment-event-consumer.md.
     */
    WALLET_NOT_FOUND,

    /**
     * A different event already credited this transaction reference: a producer contract violation, not a
     * redelivery.
     */
    DUPLICATE_REFERENCE,

    /**
     * A payout event for a reference with no withdrawal, or with a refused one: no money left the wallet for it.
     * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
     */
    WITHDRAWAL_NOT_FOUND,

    /**
     * A payout event whose wallet or amount differs from the withdrawal under its reference: a producer defect.
     */
    WITHDRAWAL_MISMATCH
}
