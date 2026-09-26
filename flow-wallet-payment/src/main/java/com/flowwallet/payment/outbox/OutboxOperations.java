package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FAILED rows are the outbox's dead-letter store. Nothing deletes them, and only {@link #requeueFailed} returns
 * them to delivery. See docs/adr/0008-transactional-outbox.md.
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
     * Returns every FAILED row to PENDING with its retry state and {@code error_message} cleared, so the cause has
     * to be read from {@code outbox_events} before a requeue.
     *
     * @return the number of rows requeued
     */
    public int requeueFailed() {
        int requeued = outboxEventRepository.requeueFailed(OutboxStatus.PENDING, OutboxStatus.FAILED);
        if (requeued > 0) {
            log.info("Requeued {} FAILED outbox events back to PENDING", requeued);
        }
        return requeued;
    }
}
