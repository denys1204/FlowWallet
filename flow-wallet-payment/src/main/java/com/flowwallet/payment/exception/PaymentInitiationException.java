package com.flowwallet.payment.exception;

public class PaymentInitiationException extends RuntimeException {
    public PaymentInitiationException(String message, Throwable cause) {
        super(message, cause);
    }
}
