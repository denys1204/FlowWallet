package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when Stripe cannot say what the platform's balance holds. Nothing was written, so the caller retries.
 * See docs/adr/0016-error-model-and-status-codes.md.
 */
public class PayoutReserveUnavailableException extends ApiException {
    public PayoutReserveUnavailableException(Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "The platform's balance could not be read from Stripe; retry.", cause);
    }
}
