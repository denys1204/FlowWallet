package com.flowwallet.wallet.withdrawal;

/**
 * Why the wallet turned a withdrawal down. A refusal is recorded under the key, so a retry with the same key gets
 * the same answer even after the cause is gone.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public enum WithdrawalRefusalReason {
    /**
     * Payment Service reported no bank account ready for payouts.
     */
    NO_BANK_ACCOUNT,
    /**
     * The balance does not cover the amount.
     */
    INSUFFICIENT_FUNDS
}
