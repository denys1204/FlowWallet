package com.flowwallet.payment.provider.exception;

/**
 * Thrown when a {@link com.flowwallet.payment.provider.dto.PaymentRequestContext}
 * fails validation before being sent to a payment provider.
 */
public class InvalidPaymentRequestException extends RuntimeException {
    public InvalidPaymentRequestException(String message) {
        super(message);
    }
}
