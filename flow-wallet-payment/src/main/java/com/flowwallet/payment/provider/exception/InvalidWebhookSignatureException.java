package com.flowwallet.payment.provider.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
