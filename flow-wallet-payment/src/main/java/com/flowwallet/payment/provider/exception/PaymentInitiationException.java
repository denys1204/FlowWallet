package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the provider fails to create the payment. Nothing was charged, and a retry with the same key is
 * safe. See docs/adr/0016-error-model-and-status-codes.md.
 */
public class PaymentInitiationException extends ApiException {
    public PaymentInitiationException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
