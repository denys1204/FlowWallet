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
}
