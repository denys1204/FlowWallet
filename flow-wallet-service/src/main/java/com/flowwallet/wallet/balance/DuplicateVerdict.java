package com.flowwallet.wallet.balance;

/**
 * Which barrier an integrity violation hit, decided by reading the database back rather than by inspecting
 * constraint names — so it survives the schema being renamed and needs no knowledge of it.
 * <p>
 * Not persisted, so it lives beside the code that uses it rather than in the enums package.
 */
enum DuplicateVerdict {

    /** This exact event was handled before. Ordinary at-least-once redelivery; nothing to do. */
    EVENT_ALREADY_PROCESSED,

    /**
     * A different event already credited this transaction reference. The producer emitted two events for
     * one payment, which is a contract violation and needs saying out loud.
     */
    REFERENCE_ALREADY_CREDITED,

    /**
     * Neither barrier. Something else in the schema refused the write — a wallet lost the race to be
     * created, a column is too narrow, a constraint was added that nothing pre-checks. Treating it as a
     * duplicate would ack an event whose credit never happened, so it must be raised.
     */
    NOT_A_DUPLICATE
}
