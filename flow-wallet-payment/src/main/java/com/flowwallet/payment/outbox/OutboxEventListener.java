package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {
    private final OutboxMessageSender outboxMessageSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOutboxCreatedEvent(OutboxCreatedEvent event) {
        log.debug("Received OutboxCreatedEvent for eventId: {}", event.outboxEventId());

        try {
            outboxMessageSender.processEvent(event.outboxEventId());
        } catch (OutboxMessageProcessingException e) {
            log.warn("Expected failure while instantly sending outbox event {}. Poller will retry. Reason: {}", event.outboxEventId(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected system error occurred while processing outbox event {}", event.outboxEventId(), e);
            throw e;
        }
    }
}