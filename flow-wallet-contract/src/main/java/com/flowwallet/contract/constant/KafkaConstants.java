package com.flowwallet.contract.constant;

/**
 * Shared Kafka topic names and header keys used across services.
 */
public final class KafkaConstants {
    private KafkaConstants() {
    }

    /**
     * Version stamped into every payment event. Bump it only for a change that cannot be made additively
     * (renaming, removing or retyping a field); an added optional field leaves it alone.
     * See docs/adr/0009-payment-event-contract.md.
     */
    public static final int PAYMENT_EVENT_SCHEMA_VERSION = 1;

    /**
     * Version stamped into every payout event, under the same rule as {@link #PAYMENT_EVENT_SCHEMA_VERSION}.
     */
    public static final int PAYOUT_EVENT_SCHEMA_VERSION = 1;

    /**
     * Version stamped into every withdrawal command, under the same rule as {@link #PAYMENT_EVENT_SCHEMA_VERSION}.
     */
    public static final int WITHDRAWAL_EVENT_SCHEMA_VERSION = 1;

    /**
     * Payment Service to Wallet Service: payment events and payout events.
     */
    public static final String PAYMENT_EVENTS_TOPIC = "payment.events";

    /**
     * Wallet Service to Payment Service: withdrawal commands. See
     * docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
     */
    public static final String WALLET_WITHDRAWALS_TOPIC = "wallet.withdrawals";

    /**
     * Names the concrete event type of a record. Consumers dispatch on it and never infer the type from the
     * fields present in the payload.
     */
    public static final String HEADER_EVENT_TYPE = "eventType";

    public static final String EVENT_TYPE_PAYMENT_COMPLETED = "PaymentCompletedEvent";

    public static final String EVENT_TYPE_PAYMENT_FAILED = "PaymentFailedEvent";

    public static final String EVENT_TYPE_PAYOUT_PAID = "PayoutPaidEvent";

    public static final String EVENT_TYPE_PAYOUT_FAILED = "PayoutFailedEvent";

    public static final String EVENT_TYPE_PAYOUT_RETURNED = "PayoutReturnedEvent";

    public static final String EVENT_TYPE_WITHDRAWAL_REQUESTED = "WithdrawalRequestedEvent";
}
