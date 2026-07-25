package com.flowwallet.payment.provider.exception;

import com.flowwallet.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the upstream payment provider fails to initiate a payment. Maps to HTTP 502 Bad Gateway
 * because the failure originates in a downstream dependency (e.g. Stripe), not in the client request.
 */
public class PaymentInitiationException extends ApiException {
    public PaymentInitiationException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
