package com.flowwallet.payment.payout;

/**
 * What became of an outcome the executor tried to record.
 */
public enum RecordResult {
    RECORDED,

    /**
     * Another writer moved the payout to another status first, so the outcome changed nothing.
     */
    TAKEN_BY_TRANSITION,

    /**
     * The lease ran out and another claim owns the step, so the outcome changed nothing.
     */
    TAKEN_BY_CLAIM
}
