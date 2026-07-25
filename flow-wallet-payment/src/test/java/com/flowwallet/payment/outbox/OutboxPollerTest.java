package com.flowwallet.payment.outbox;

import com.flowwallet.payment.config.OutboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxPollerTest {
    private OutboxEventRepository repository;
    private OutboxMessageSender sender;
    private OutboxProperties properties;
    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        sender = mock(OutboxMessageSender.class);
        properties = new OutboxProperties();
        poller = new OutboxPoller(repository, sender, properties);
    }

    @Test
    void reapStuckProcessingResetsEventsOlderThanThreshold() {
        poller.reapStuckProcessing();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(repository).resetStuckProcessing(
                eq(OutboxStatus.PENDING),
                eq(OutboxStatus.PROCESSING),
                threshold.capture()
        );
        long agoMs = Duration.between(threshold.getValue(), Instant.now()).toMillis();
        assertThat(agoMs).isBetween(
                properties.getStuckProcessingThresholdMs() - 5000,
                properties.getStuckProcessingThresholdMs() + 5000
        );
    }

    @Test
    void pollOutboxProcessesEachDispatchableEvent() {
        when(repository.findDispatchable(
                        eq(OutboxStatus.PENDING),
                        any(),
                        any(Pageable.class)
                )
        ).thenReturn(List.of(event(1L), event(2L)));

        poller.pollOutbox();

        verify(sender).processEvent(1L);
        verify(sender).processEvent(2L);
    }

    @Test
    void pollOutboxContinuesAfterAFailingEvent() {
        when(repository.findDispatchable(eq(OutboxStatus.PENDING), any(), any(Pageable.class)))
                .thenReturn(List.of(event(1L), event(2L)));
        doThrow(new OutboxMessageProcessingException("boom")).when(sender).processEvent(1L);

        poller.pollOutbox();

        verify(sender).processEvent(1L);
        verify(sender).processEvent(2L);
    }

    @Test
    void startupResetFlipsProcessingToPending() {
        poller.resetStuckEvents();

        verify(repository).resetStuckEvents(OutboxStatus.PENDING, OutboxStatus.PROCESSING);
    }

    private OutboxEvent event(Long id) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType("PaymentTransaction")
                .aggregateId("ref-" + id)
                .eventType("PaymentCompletedEvent")
                .payload("{}")
                .build();
    }
}
