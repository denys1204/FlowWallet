package com.flowwallet.payment.payout;

/**
 * A record on {@code wallet.withdrawals} that cannot become a payout, whatever the retries: no or another event type,
 * no value, unparseable JSON, or a command whose shape is invalid. The error handler dead-letters it at once.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
public class UnreadableWithdrawalCommandException extends RuntimeException {
    public UnreadableWithdrawalCommandException(String message) {
        super(message);
    }

    public UnreadableWithdrawalCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
