package com.flowwallet.payment.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
