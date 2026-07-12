package com.flowwallet.common.constant;

/**
 * Shared Kafka topic names and header keys used across services.
 */
public final class KafkaConstants {
    private KafkaConstants() {
        // utility class — no instantiation
    }

    /** Topic for payment lifecycle events (e.g. PaymentCompletedEvent). */
    public static final String PAYMENT_EVENTS_TOPIC = "payment.events";

    /** Dead-letter topic for failed payment event processing. */
    public static final String PAYMENT_EVENTS_DLT = "payment.events.DLT";

    /** Kafka header carrying the transaction reference for tracing. */
    public static final String HEADER_TRANSACTION_REFERENCE = "X-Transaction-Reference";

    /** Event type for payment completion */
    public static final String EVENT_TYPE_PAYMENT_COMPLETED = "PaymentCompletedEvent";

    /** Aggregate type for payment transaction */
    public static final String AGGREGATE_TYPE_PAYMENT_TRANSACTION = "PaymentTransaction";

    /** Event type for payment failure */
    public static final String EVENT_TYPE_PAYMENT_FAILED = "PaymentFailedEvent";
}
