package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Actuator ops endpoint for the transactional outbox, exposed under management (not the public API) at
 * {@code /actuator/outbox}. Lets an operator see the FAILED backlog (GET) and requeue it (POST) after the
 * failure cause is resolved.
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
