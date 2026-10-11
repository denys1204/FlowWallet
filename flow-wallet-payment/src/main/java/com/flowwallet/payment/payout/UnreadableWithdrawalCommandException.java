package com.flowwallet.payment.payout;

import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;

/**
 * A record on {@code wallet.withdrawals} that cannot become a payout, whatever the retries: no or another event type,
 * no value, unparseable JSON, or a command whose shape is invalid. The error handler dead-letters it at once. The
 * message reaches the retry listener's log lines and the dead letter's headers, so it never quotes the raw value or
 * Jackson's message.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md and
 * docs/adr/0044-unreadable-kafka-records-are-reported-without-their-content.md.
 */
public class UnreadableWithdrawalCommandException extends RuntimeException {
    public UnreadableWithdrawalCommandException(String message) {
        super(message);
    }

    /**
     * Names only the parser's error type and where it stopped. Jackson's own message quotes the input, a user id
     * included, so the parse failure is neither quoted nor kept as the cause.
     */
    public static UnreadableWithdrawalCommandException unparseable(JacksonException failure) {
        TokenStreamLocation at = failure.getLocation();
        String error = failure.getClass().getSimpleName();
        String where = at == null
                ? error
                : "%s at line %d, column %d".formatted(error, at.getLineNr(), at.getColumnNr());
        return new UnreadableWithdrawalCommandException("Withdrawal command is not readable JSON: " + where);
    }
}
