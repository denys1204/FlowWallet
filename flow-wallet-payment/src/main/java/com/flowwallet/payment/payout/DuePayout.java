package com.flowwallet.payment.payout;

/**
 * A row {@link PayoutRepository#findDue} selected: only a hint, which the claim checks again.
 */
public record DuePayout(Long id, PayoutStatus status) {
}
