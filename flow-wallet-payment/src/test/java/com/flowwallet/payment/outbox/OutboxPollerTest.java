package com.flowwallet.payment.outbox;

import com.flowwallet.payment.config.OutboxProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @AfterEach
    void clearInterruptFlag() {
        // The interrupt test sets the flag on this thread; it must not leak into the next test.
        Thread.interrupted();
    }

    @Test
    void cleanupDeletesOnlyCompletedRowsPastTheRetention() {
        // A FAILED row is an event that never reached Kafka -- for a completed payment, a credit that never
        // happened -- and it is the only record of it. The repository method can delete COMPLETED rows only.
        poller.cleanupOldEvents();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteCompletedBefore(cutoff.capture());
        long ageDays = Duration.between(cutoff.getValue(), Instant.now()).toDays();
        assertThat(ageDays).isEqualTo(properties.getRetentionDays());
    }

    @Test
    void reapStuckProcessingResetsEventsOlderThanThreshold() {
        // A threshold much shorter than configured would reset rows a live sender is still publishing.
        poller.reapStuckProcessing();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(repository).resetProcessingClaimedBefore(threshold.capture());
        long agoMs = Duration.between(threshold.getValue(), Instant.now()).toMillis();
        assertThat(agoMs).isBetween(
                properties.getStuckProcessingThresholdMs() - 5000,
                properties.getStuckProcessingThresholdMs() + 5000
        );
    }

    @Test
    void pollOutboxProcessesEachDispatchableEvent() {
        // The poller is the only path for rows the fast path missed; skipping one would strand its event.
        givenDispatchable(event(1L), event(2L));

        poller.pollOutbox();

        verify(sender).processEvent(1L);
        verify(sender).processEvent(2L);
    }

    @Test
    void pollOutboxContinuesAfterAFailedAttempt() {
        // One failing row must not block other payments' events.
        givenDispatchable(event(1L), event(2L));
        doThrow(new OutboxMessageProcessingException("boom")).when(sender).processEvent(1L);

        poller.pollOutbox();

        verify(sender).processEvent(2L);
    }

    @Test
    void pollOutboxContinuesAfterAnUnexpectedError() {
        // A database error after a delivered send is not a failed attempt and arrives as a plain RuntimeException.
        // Uncaught, it aborted the whole batch.
        givenDispatchable(event(1L), event(2L));
        doThrow(new DataAccessResourceFailureException("db")).when(sender).processEvent(1L);

        poller.pollOutbox();

        verify(sender).processEvent(2L);
    }

    @Test
    void pollOutboxStopsClaimingRowsOnceInterrupted() {
        // After an interrupt (shutdown) every further claim would start a send that cannot finish.
        givenDispatchable(event(1L), event(2L));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(sender).processEvent(1L);

        poller.pollOutbox();

        verify(sender, never()).processEvent(2L);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void startupResetFlipsProcessingToPending() {
        // Without it, rows a crashed sender left behind would wait for the reaper's threshold.
        poller.resetStuckEvents();

        verify(repository).resetAllProcessingToPending();
    }

    private void givenDispatchable(OutboxEvent... events) {
        when(repository.findDispatchable(any(), any(Pageable.class))).thenReturn(List.of(events));
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
