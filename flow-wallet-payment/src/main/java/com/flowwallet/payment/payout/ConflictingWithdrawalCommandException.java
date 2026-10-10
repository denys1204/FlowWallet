package com.flowwallet.payment.payout;

/**
 * A command under a reference already accepted with other terms: a producer defect, never a redelivery. The error
 * handler dead-letters it at once. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
public class ConflictingWithdrawalCommandException extends RuntimeException {
    public ConflictingWithdrawalCommandException(String transactionReference) {
        super("Withdrawal " + transactionReference + " was already accepted with other terms");
    }
}
