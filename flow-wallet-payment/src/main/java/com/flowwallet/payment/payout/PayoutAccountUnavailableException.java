package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when Stripe gives no answer that settles the bank account. Every Stripe call under it is keyed, so a retry
 * with the same token is safe. See docs/adr/0016-error-model-and-status-codes.md.
 */
public class PayoutAccountUnavailableException extends ApiException {
    public PayoutAccountUnavailableException() {
        super(HttpStatus.BAD_GATEWAY, "The bank account could not be set at Stripe; retry with the same token.");
    }
}
