package com.flowwallet.payment.outbox;

public class OutboxMessageProcessingException extends RuntimeException {
    public OutboxMessageProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
