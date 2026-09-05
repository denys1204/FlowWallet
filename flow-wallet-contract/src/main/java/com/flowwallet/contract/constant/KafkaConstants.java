package com.flowwallet.contract.constant;

/**
 * Shared Kafka topic names and header keys used across services.
 */
public final class KafkaConstants {
    private KafkaConstants() {
    }

    /** Topic for payment lifecycle events (e.g. PaymentCompletedEvent). */
    /**
     * Version stamped into every payment event. Bump it only when a change cannot be made
     * additively — renaming, removing or retyping a field. Adding an optional field does not
     * warrant a bump, because consumers ignore what they do not know.
     */
    public static final int PAYMENT_EVENT_SCHEMA_VERSION = 1;

    public static final String PAYMENT_EVENTS_TOPIC = "payment.events";


    /**
     * Carries the concrete event type alongside the message. The payload alone does not say which
     * record it is, and telling {@code PaymentCompletedEvent} from {@code PaymentFailedEvent} by
     * guessing at present fields breaks on the first schema change.
     */
    public static final String HEADER_EVENT_TYPE = "eventType";

    /**
     * Event type for payment completion
     */
    public static final String EVENT_TYPE_PAYMENT_COMPLETED = "PaymentCompletedEvent";

    /**
     * Aggregate type for payment transaction
     */
    public static final String AGGREGATE_TYPE_PAYMENT_TRANSACTION = "PaymentTransaction";

    /**
     * Event type for payment failure
     */
    public static final String EVENT_TYPE_PAYMENT_FAILED = "PaymentFailedEvent";
}
