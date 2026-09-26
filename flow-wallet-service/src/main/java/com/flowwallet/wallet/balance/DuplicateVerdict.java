package com.flowwallet.wallet.balance;

/**
 * Which barrier an integrity violation hit, as {@link PaymentEventOutcomeStore#classify} reads it back. Not
 * persisted, so it lives beside its user rather than in the enums package.
 * See docs/adr/0010-idempotent-payment-event-consumer.md.
 */
enum DuplicateVerdict {

    /**
     * This exact event was handled before: an ordinary redelivery.
     */
    EVENT_ALREADY_PROCESSED,

    /**
     * A different event already credited this transaction reference, which is a producer contract violation.
     */
    REFERENCE_ALREADY_CREDITED,

    /**
     * Neither barrier: another constraint refused the write and the credit did not happen, so it must be
     * rethrown, never acknowledged.
     */
    NOT_A_DUPLICATE
}
