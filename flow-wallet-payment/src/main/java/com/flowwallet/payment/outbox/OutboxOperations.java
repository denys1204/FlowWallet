package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Operational view over the outbox: reporting how many events have exhausted their retries (FAILED) and
 * providing a manual requeue so an operator can retry them once the underlying cause is fixed. FAILED rows
 * are a durable dead-letter store (kept by the cleanup job for the retention window), so nothing is lost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxOperations {
    private final OutboxEventRepository outboxEventRepository;

    public long failedCount() {
        return outboxEventRepository.countByStatus(OutboxStatus.FAILED);
    }

    /**
     * Returns every FAILED event to PENDING with a clean retry state (retryCount reset, backoff cleared),
     * so the poller picks them up again. Intended to be triggered by an operator after the failure cause
     * (e.g. broker outage) is resolved.
     *
     * @return the number of events requeued
     */
    public int requeueFailed() {
        int requeued = outboxEventRepository.requeueFailed(OutboxStatus.PENDING, OutboxStatus.FAILED);
        if (requeued > 0) {
            log.info("Requeued {} FAILED outbox events back to PENDING", requeued);
        }
        return requeued;
    }
}
