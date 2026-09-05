package com.flowwallet.wallet.balance;

/**
 * A record that cannot be turned into an event the wallet could even record a refusal for — malformed JSON,
 * a missing or unrecognised type header, or no event id to deduplicate on.
 * <p>
 * Deliberately not an {@code ApiException}: a Kafka listener has no HTTP response, so a status code
 * attached here would mean nothing and mislead whoever read it next.
 * <p>
 * These reach the dead-letter topic rather than a row, because the row would need an event id this record
 * does not have.
 */
public class UnreadablePaymentEventException extends RuntimeException {
    private UnreadablePaymentEventException(String message) {
        super(message);
    }

    private UnreadablePaymentEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public static UnreadablePaymentEventException missingEventType() {
        return new UnreadablePaymentEventException(
                "Record carries no eventType header, and the payload is not allowed to imply the type"
        );
    }

    public static UnreadablePaymentEventException unknownEventType(String eventType) {
        return new UnreadablePaymentEventException("Unrecognised eventType header: " + eventType);
    }

    public static UnreadablePaymentEventException unparseable(String eventType, Throwable cause) {
        return new UnreadablePaymentEventException("Record is not a readable " + eventType, cause);
    }

    public static UnreadablePaymentEventException missingEventId(String eventType) {
        return new UnreadablePaymentEventException(
                "%s carries no eventId, so it can be neither deduplicated nor recorded".formatted(eventType)
        );
    }
}
