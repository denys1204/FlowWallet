package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when Stripe cannot be asked what an operator's action depends on. Nothing was written, so the operator
 * retries. See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
public class PayoutCheckUnavailableException extends ApiException {
    public PayoutCheckUnavailableException(Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "Stripe could not be asked about the payout. Nothing was changed; retry.", cause);
    }
}
