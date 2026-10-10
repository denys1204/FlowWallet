package com.flowwallet.outbox;

public class OutboxMessageProcessingException extends RuntimeException {
    public OutboxMessageProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public OutboxMessageProcessingException(String message) {
        super(message);
    }
}
