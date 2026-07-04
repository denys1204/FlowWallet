package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {
    private final OutboxMessageSender outboxMessageSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOutboxCreatedEvent(OutboxCreatedEvent event) {
        log.debug("Received OutboxCreatedEvent for eventId: {}", event.outboxEventId());
        try {
            outboxMessageSender.processEvent(event.outboxEventId());
        } catch (Exception e) {
            log.warn("Failed to instantly send outbox event {}. Poller will retry it.", event.outboxEventId());
        }
    }
}
