package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The fast path: sends a row as soon as the transaction that wrote it commits. Whatever goes wrong here, the row
 * stays in the table for the poller or the reaper, so a failure is logged once and never rethrown: the async
 * executor would only log it a second time. See docs/adr/0008-transactional-outbox.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {
    private final OutboxMessageSender outboxMessageSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOutboxCreatedEvent(OutboxCreatedEvent event) {
        log.debug("Received OutboxCreatedEvent for outbox event {}", event.outboxEventId());

        try {
            outboxMessageSender.processEvent(event.outboxEventId());
        } catch (OutboxMessageProcessingException e) {
            log.warn(
                    "Fast path could not send outbox event {}; the poller retries it. {}",
                    event.outboxEventId(),
                    e.getMessage()
            );
        } catch (RuntimeException e) {
            log.error("Unexpected error while sending outbox event {} on the fast path", event.outboxEventId(), e);
        }
    }
}
