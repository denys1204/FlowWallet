package com.flowwallet.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxTest {
    private static final long SAVED_ID = 42L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxEventRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private Outbox outbox;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(repository.save(any(OutboxEvent.class))).thenReturn(OutboxEvent.builder().id(SAVED_ID).build());
        outbox = new Outbox(repository, eventPublisher, objectMapper);
    }

    @Test
    void anEventIsQueuedAsJsonUnderItsAggregateAndEventType() {
        // Guards the row the sender reads: a wrong key reorders an aggregate's events, a wrong header misroutes them
        // at the consumer, and a row that is not PENDING is never sent.
        outbox.enqueue("TestAggregate", "ref-1", "TestEvent", new TestEvent("ref-1", 5));

        OutboxEvent saved = savedRow();
        assertThat(saved.getAggregateType()).isEqualTo("TestAggregate");
        assertThat(saved.getAggregateId()).isEqualTo("ref-1");
        assertThat(saved.getEventType()).isEqualTo("TestEvent");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertThat(payload.get("reference").asString()).isEqualTo("ref-1");
        assertThat(payload.get("amount").asInt()).isEqualTo(5);
    }

    @Test
    void theFastPathIsToldTheIdOfTheSavedRow() {
        // The listener claims the row by this id after the commit; a wrong or null id leaves every event to the poller.
        outbox.enqueue("TestAggregate", "ref-1", "TestEvent", new TestEvent("ref-1", 5));

        verify(eventPublisher).publishEvent(new OutboxCreatedEvent(SAVED_ID));
    }

    @Test
    void anEventThatCannotBeWrittenAsJsonQueuesNothing() {
        // Guards a row whose payload failed to serialize being saved or announced anyway.
        assertThatThrownBy(() -> outbox.enqueue("TestAggregate", "ref-1", "TestEvent", new Unwritable()))
                .isInstanceOf(EventSerializationException.class)
                .hasMessageContaining("TestAggregate ref-1");

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aNullEventIsRefusedBeforeAnythingIsQueued() {
        // Guards a row with the payload "null", which would be sent and then dead-lettered by the consumer.
        assertThatThrownBy(() -> outbox.enqueue("TestAggregate", "ref-1", "TestEvent", null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(repository, eventPublisher);
    }

    @Test
    void theOutboxRefusesToRunOutsideTheStateChangeTransaction() {
        // Without MANDATORY a call from outside the state change's transaction would commit the row on its own: the
        // dual write the outbox exists to prevent.
        Transactional transactional = AnnotationUtils.findAnnotation(Outbox.class, Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private OutboxEvent savedRow() {
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    private record TestEvent(String reference, int amount) {
    }

    private static final class Unwritable {
        public String getValue() {
            throw new IllegalStateException("no value");
        }
    }
}
