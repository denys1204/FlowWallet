package com.flowwallet.payment.payout;

import java.util.Set;

/**
 * Where a payout stands in its Transfer, Payout and Reversal steps. The executor works the steps in
 * {@code ACCEPTED}, {@code TRANSFERRED} and {@code REVERSING}; the Connect webhook and the reconciler settle
 * {@code PAYOUT_CREATED} and {@code PAID}; {@code RETURNED} is final; an operator takes over in
 * {@code NEEDS_ATTENTION}. See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public enum PayoutStatus {
    ACCEPTED,
    TRANSFERRED,
    PAYOUT_CREATED,
    PAID,
    REVERSING,
    RETURNED,
    NEEDS_ATTENTION;

    private static final Set<PayoutStatus> EXECUTOR_STEPS = Set.of(ACCEPTED, TRANSFERRED, REVERSING);

    /**
     * Whether {@code PayoutExecutor} works a payout in this status. A move into one makes the row due at once.
     */
    public boolean isExecutorStep() {
        return EXECUTOR_STEPS.contains(this);
    }
}
