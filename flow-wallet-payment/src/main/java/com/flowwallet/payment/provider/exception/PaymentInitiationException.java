package com.flowwallet.payment.provider.exception;

public class PaymentInitiationException extends RuntimeException {
    public PaymentInitiationException(String message, Throwable cause) {
        super(message, cause);
    }
}
