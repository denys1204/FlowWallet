package com.flowwallet.payment.payout;

/**
 * Whether a user can be paid out. {@code READY} means the connected account has {@code payouts_enabled} and its
 * default external account for the currency has a status Stripe pays out to ({@code new}, {@code validated} or
 * {@code verified}); anything else, including no account, is {@code NOT_SET}.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public enum BankAccountState {
    READY,
    NOT_SET
}
