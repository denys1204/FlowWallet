package com.flowwallet.wallet.withdrawal;

/**
 * A payout event whose wallet or amount differs from the withdrawal under its reference, which only a producer
 * defect explains. Thrown inside the transaction, so the barrier row rolls back with it before the refusal is
 * recorded. See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public class WithdrawalMismatchException extends RuntimeException {
    public WithdrawalMismatchException() {
        super("The event's wallet or amount differs from the withdrawal under its reference");
    }
}
