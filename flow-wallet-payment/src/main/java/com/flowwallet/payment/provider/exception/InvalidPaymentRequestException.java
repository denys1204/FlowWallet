package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a provider's checks refuse a {@link com.flowwallet.payment.provider.dto.PaymentRequestContext}
 * before the transaction row is reserved.
 */
public class InvalidPaymentRequestException extends ApiException {
    public InvalidPaymentRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
