package com.flowwallet.payment.payout;

/**
 * Why the executor handed a payout to an operator. Logged and counted; the row records only that it needs attention
 * and the step it came from.
 */
public enum AttentionCause {
    /**
     * The step's outcome stayed unknown for the configured number of tries.
     */
    UNKNOWN_LIMIT,

    /**
     * An idempotency key was sent with other parameters, which only a defect causes.
     */
    DEFECT,

    /**
     * Stripe holds an object for the reference whose terms or destination are not the payout's.
     */
    MISMATCH,

    /**
     * Stripe holds more than one transfer, or more than one live payout, for the reference.
     */
    MULTIPLE,

    /**
     * A reversal was due while a payout for the reference may still reach the bank.
     */
    LIVE_PAYOUT,

    /**
     * Stripe kept refusing the reversal for the configured number of tries.
     */
    REVERSAL_REFUSED
}
