package com.flowwallet.payment.outbox;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.payment.transaction.PaymentTransaction;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Writes a payment event to {@code outbox_events} inside the transaction that changes the payment's status.
 * {@code MANDATORY} makes a call without that transaction fail instead of writing a row the status change does not
 * own, which would be a dual write. See docs/adr/0008-transactional-outbox.md.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentOutboxService {
    /**
     * Labels a row in Payment Service's own table and never reaches the wire, so it stays out of
     * flow-wallet-contract. See docs/adr/0002-module-boundaries.md.
     */
    static final String AGGREGATE_TYPE_PAYMENT_TRANSACTION = "PaymentTransaction";

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public void publishPaymentCompleted(PaymentTransaction tx, Instant completedAt) {
        enqueue(tx, eventMapper.toPaymentCompletedEvent(tx, completedAt));
    }

    public void publishPaymentFailed(PaymentTransaction tx, String reason, Instant failedAt) {
        enqueue(tx, eventMapper.toPaymentFailedEvent(tx, reason, failedAt));
    }

    private void enqueue(PaymentTransaction tx, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new EventSerializationException(
                    "Failed to serialize " + event.getClass().getSimpleName() + " for tx: "
                            + tx.getTransactionReference(),
                    e
            );
        }

        OutboxEvent saved = outboxEventRepository.save(OutboxEvent.pending(
                AGGREGATE_TYPE_PAYMENT_TRANSACTION,
                tx.getTransactionReference(),
                eventTypeOf(event),
                payload
        ));
        eventPublisher.publishEvent(new OutboxCreatedEvent(saved.getId()));
    }

    /**
     * The only place the {@code eventType} header is chosen. The wallet dispatches on the header alone, so a
     * completed header on a failure payload would credit a payment that failed.
     */
    private static String eventTypeOf(Object event) {
        return switch (event) {
            case PaymentCompletedEvent _ -> KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED;
            case PaymentFailedEvent _ -> KafkaConstants.EVENT_TYPE_PAYMENT_FAILED;
            default -> throw new IllegalArgumentException("No eventType for " + event.getClass().getName());
        };
    }
}
