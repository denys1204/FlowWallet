package com.flowwallet.wallet.withdrawal;

/**
 * A payout event for a reference with no withdrawal, or with a refused one, under which no money left the wallet.
 * Thrown inside the transaction, so the barrier row rolls back with it before the refusal is recorded.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public class UnknownWithdrawalException extends RuntimeException {
    public UnknownWithdrawalException() {
        super("No withdrawal that left the wallet is held under the event's reference");
    }
}
