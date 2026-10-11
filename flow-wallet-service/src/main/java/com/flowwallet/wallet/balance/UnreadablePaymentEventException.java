package com.flowwallet.wallet.balance;

import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;

/**
 * A record that cannot be turned into an event the wallet could record a refusal for: malformed JSON, no value or
 * a JSON {@code null}, a missing or unrecognised type header, or no event id. It is not retried and goes to the
 * dead-letter topic, because a row would need an event id. Not an {@code ApiException}, because a Kafka listener
 * has no HTTP response. The message reaches the retry listener's log lines and the dead letter's headers, so it never
 * quotes the raw value, a header the wallet does not recognise or Jackson's message.
 * See docs/adr/0010-idempotent-payment-event-consumer.md and
 * docs/adr/0044-unreadable-kafka-records-are-reported-without-their-content.md.
 */
public class UnreadablePaymentEventException extends RuntimeException {
    private UnreadablePaymentEventException(String message) {
        super(message);
    }

    public static UnreadablePaymentEventException missingEventType() {
        return new UnreadablePaymentEventException(
                "Record carries no eventType header, and the payload is not allowed to imply the type"
        );
    }

    public static UnreadablePaymentEventException unknownEventType() {
        return new UnreadablePaymentEventException("Unrecognised eventType header");
    }

    /**
     * Names only the parser's error type and where it stopped. Jackson's own message quotes the input, a user id
     * included, so the parse failure is neither quoted nor kept as the cause.
     */
    public static UnreadablePaymentEventException unparseable(String eventType, JacksonException failure) {
        TokenStreamLocation at = failure.getLocation();
        String error = failure.getClass().getSimpleName();
        String where = at == null
                ? error
                : "%s at line %d, column %d".formatted(error, at.getLineNr(), at.getColumnNr());
        return new UnreadablePaymentEventException("Record is not a readable %s: %s".formatted(eventType, where));
    }

    public static UnreadablePaymentEventException noEvent(String eventType) {
        return new UnreadablePaymentEventException("Record has a %s header but no event".formatted(eventType));
    }

    public static UnreadablePaymentEventException missingEventId(String eventType) {
        return new UnreadablePaymentEventException(
                "%s carries no eventId, so it can be neither deduplicated nor recorded".formatted(eventType)
        );
    }
}
