package com.flowwallet.payment.outbox;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.flowwallet.common.event.PaymentCompletedEvent;
import com.flowwallet.common.event.PaymentFailedEvent;
import com.flowwallet.payment.transaction.PaymentTransaction;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOutboxService {
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public void publishPaymentCompleted(PaymentTransaction tx) {
        try {
            PaymentCompletedEvent event = eventMapper.toPaymentCompletedEvent(tx);
            OutboxEvent outboxEvent = eventMapper.toOutboxEvent(tx, objectMapper.writeValueAsString(event));
            outboxEventRepository.save(outboxEvent);
            eventPublisher.publishEvent(new OutboxCreatedEvent(outboxEvent.getId()));
        } catch (JacksonException e) {
            throw new EventSerializationException("Failed to serialize PaymentCompletedEvent for tx: " + tx.getTransactionReference(), e);
        }
    }

    public void publishPaymentFailed(PaymentTransaction tx, String reason) {
        try {
            PaymentFailedEvent event = eventMapper.toPaymentFailedEvent(tx, reason);
            OutboxEvent outboxEvent = eventMapper.toFailedOutboxEvent(tx, objectMapper.writeValueAsString(event));
            outboxEventRepository.save(outboxEvent);
            eventPublisher.publishEvent(new OutboxCreatedEvent(outboxEvent.getId()));
        } catch (JacksonException e) {
            throw new EventSerializationException("Failed to serialize PaymentFailedEvent for tx: " + tx.getTransactionReference(), e);
        }
    }
}
