package com.flowwallet.payment.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
