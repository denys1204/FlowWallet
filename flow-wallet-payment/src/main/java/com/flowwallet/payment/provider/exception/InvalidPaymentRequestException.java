package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a {@link com.flowwallet.payment.provider.dto.PaymentRequestContext}
 * fails validation before being sent to a payment provider. Maps to HTTP 400 Bad Request.
 */
public class InvalidPaymentRequestException extends ApiException {
    public InvalidPaymentRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
