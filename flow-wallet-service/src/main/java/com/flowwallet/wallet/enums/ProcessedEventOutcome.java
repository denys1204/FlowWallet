package com.flowwallet.wallet.enums;

/**
 * What the wallet did with a payment or payout event. Every name fits the {@code VARCHAR(20)} column that stores it.
 */
public enum ProcessedEventOutcome {
    CREDITED,
    FAILURE_RECORDED,
    REJECTED,
    /**
     * A {@code PayoutReturnedEvent} credited a withdrawal's amount back.
     */
    RETURN_CREDITED,
    /**
     * A {@code PayoutPaidEvent} or {@code PayoutFailedEvent} handled, whether or not it raised the withdrawal's
     * status. It moves no money.
     */
    STATUS_RECORDED
}
