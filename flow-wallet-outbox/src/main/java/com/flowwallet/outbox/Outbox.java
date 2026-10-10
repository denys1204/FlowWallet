package com.flowwallet.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Writes an event to {@code outbox_events} inside the transaction that changes the state the event reports.
 * {@code MANDATORY} makes a call without that transaction fail instead of writing a row the state change does not
 * own, which would be a dual write. After the commit {@link OutboxEventListener} sends the row, and
 * {@link OutboxPoller} sends whatever that missed. See docs/adr/0008-transactional-outbox.md.
 */
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class Outbox {
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Queues {@code event} as JSON, to be sent with {@code aggregateId} as the record key and {@code eventType} as
     * the event type header.
     *
     * @param aggregateType labels the row in the service's own table and never reaches the wire
     * @throws EventSerializationException if the event cannot be written as JSON; nothing is queued
     */
    public void enqueue(String aggregateType, String aggregateId, String eventType, Object event) {
        // Jackson writes a null event as the payload "null", which would be sent and dead-lettered by the consumer.
        Objects.requireNonNull(event, "event");

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new EventSerializationException(
                    "Failed to serialize " + event.getClass().getSimpleName() + " for " + aggregateType + " "
                            + aggregateId,
                    e
            );
        }

        OutboxEvent saved = outboxEventRepository.save(
                OutboxEvent.pending(aggregateType, aggregateId, eventType, payload)
        );
        eventPublisher.publishEvent(new OutboxCreatedEvent(saved.getId()));
    }
}
