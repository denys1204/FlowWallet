package com.flowwallet.payment.payout;

/**
 * Why a payout's money comes back, sent as the {@code reasonCode} string of the Failed and Returned events. The wire
 * carries a string from an open set, so a consumer never depends on this enum.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public enum PayoutReason {
    /**
     * Stripe refused the transfer because the platform's available balance was short.
     */
    PLATFORM_FUNDS_SHORT,
    /**
     * Stripe refused the transfer for another reason.
     */
    TRANSFER_REFUSED,
    /**
     * The user's bank account was not ready for payouts.
     */
    BANK_ACCOUNT_NOT_SET,
    /**
     * Stripe refused the payout for a reason about the bank account.
     */
    BANK_ACCOUNT_REJECTED,
    /**
     * Stripe failed, canceled or refused the payout, or its funds never became available in the connected account.
     */
    PAYOUT_FAILED,
    /**
     * The command no longer met the terms configuration sets, such as the currency list or the minimum.
     */
    TERMS_REFUSED,
    /**
     * An operator returned the payout from {@code NEEDS_ATTENTION} without a reversal.
     */
    OPERATOR_RETURNED
}
