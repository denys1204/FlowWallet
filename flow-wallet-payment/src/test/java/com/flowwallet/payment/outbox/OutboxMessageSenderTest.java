package com.flowwallet.payment.outbox;

import com.flowwallet.payment.config.OutboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxMessageSenderTest {
    private OutboxEventRepository repository;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private OutboxProperties properties;
    private OutboxMessageSender sender;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new OutboxProperties();
        sender = new OutboxMessageSender(repository, kafkaTemplate, properties);
    }

    @Test
    void backoffGrowsExponentiallyAndCaps() {
        assertThat(OutboxMessageSender.backoffMillis(0, 1000, 60000)).isEqualTo(1000);
        assertThat(OutboxMessageSender.backoffMillis(1, 1000, 60000)).isEqualTo(2000);
        assertThat(OutboxMessageSender.backoffMillis(2, 1000, 60000)).isEqualTo(4000);
        assertThat(OutboxMessageSender.backoffMillis(10, 1000, 60000)).isEqualTo(60000);
        assertThat(OutboxMessageSender.backoffMillis(1000, 1000, 60000)).isEqualTo(60000);
    }

    @Test
    void failedSendSchedulesRetryWithFutureBackoff() {
        when(repository.lockForProcessing(eq(1L), eq(OutboxStatus.PROCESSING), eq(OutboxStatus.PENDING), any())).thenReturn(1);
        when(repository.findById(1L)).thenReturn(Optional.of(pendingEvent()));
        when(kafkaTemplate.send(anyString(), any(), any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("kafka down"))
        );

        Instant before = Instant.now();

        assertThatThrownBy(
                () -> sender.processEvent(1L)
        ).isInstanceOf(OutboxMessageProcessingException.class);

        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).incrementRetryOrFail(
                eq(1L),
                anyString(),
                eq(3),
                eq(OutboxStatus.FAILED),
                eq(OutboxStatus.PENDING),
                nextAttempt.capture()
        );
        assertThat(nextAttempt.getValue()).isAfter(before);
    }

    @Test
    void successfulSendMarksCompleted() {
        when(repository.lockForProcessing(eq(1L), eq(OutboxStatus.PROCESSING), eq(OutboxStatus.PENDING), any())).thenReturn(1);
        when(repository.findById(1L)).thenReturn(Optional.of(pendingEvent()));
        when(kafkaTemplate.send(anyString(), any(), any())).thenReturn(
                CompletableFuture.completedFuture(mock(SendResult.class))
        );

        sender.processEvent(1L);

        verify(repository).markAsCompleted(eq(1L), eq(OutboxStatus.COMPLETED), any());
    }

    @Test
    void skipsWhenLockNotAcquired() {
        when(repository.lockForProcessing(eq(1L), eq(OutboxStatus.PROCESSING), eq(OutboxStatus.PENDING), any())).thenReturn(0);

        sender.processEvent(1L);

        verify(repository, never()).findById(any());
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.builder()
                .id(1L)
                .aggregateType("PaymentTransaction")
                .aggregateId("ref-1")
                .eventType("PaymentCompletedEvent")
                .payload("{}")
                .build();
    }
}
