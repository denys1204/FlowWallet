package com.flowwallet.payment.provider.dto;

/**
 * Where a payout stands at the provider. A payout reported paid can still fail later.
 */
public enum ProviderPayoutStatus {
    PENDING,
    IN_TRANSIT,
    PAID,
    FAILED,
    CANCELED,

    /**
     * A status this service does not know, treated as one whose money may still reach the bank.
     */
    UNRECOGNIZED;

    /**
     * Whether the money may reach, or has reached, the bank. Such a payout is never reversed.
     * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
     */
    public boolean mayReachTheBank() {
        return this != FAILED && this != CANCELED;
    }
}
