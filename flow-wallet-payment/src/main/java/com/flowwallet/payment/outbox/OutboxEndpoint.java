package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GET counts the FAILED outbox rows and POST requeues them. The endpoint has no authentication of its own and is
 * only as protected as Payment Service's port. See docs/adr/0008-transactional-outbox.md.
 */
@Component
@Endpoint(id = "outbox")
@RequiredArgsConstructor
public class OutboxEndpoint {
    private final OutboxOperations outboxOperations;

    @ReadOperation
    public Map<String, Long> failed() {
        return Map.of("failed", outboxOperations.failedCount());
    }

    @WriteOperation
    public Map<String, Integer> requeueFailed() {
        return Map.of("requeued", outboxOperations.requeueFailed());
    }
}
