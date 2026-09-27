package com.flowwallet.wallet.balance;

/**
 * A record that cannot be turned into an event the wallet could record a refusal for: malformed JSON, no value or
 * a JSON {@code null}, a missing or unrecognised type header, or no event id. It is not retried and goes to the dead-letter topic, because a
 * row would need an event id. Not an {@code ApiException}, because a Kafka listener has no HTTP response.
 * See docs/adr/0010-idempotent-payment-event-consumer.md.
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

    public static UnreadablePaymentEventException noEvent(String eventType) {
        return new UnreadablePaymentEventException("Record has a %s header but no event".formatted(eventType));
    }

    public static UnreadablePaymentEventException missingEventId(String eventType) {
        return new UnreadablePaymentEventException(
                "%s carries no eventId, so it can be neither deduplicated nor recorded".formatted(eventType)
        );
    }
}
