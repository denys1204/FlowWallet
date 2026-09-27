package com.flowwallet.payment.outbox;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.config.OutboxProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxProperties properties;
    private OutboxMessageSender sender;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new OutboxProperties();
        properties.setMaxRetries(3);
        sender = new OutboxMessageSender(repository, kafkaTemplate, properties);
    }

    @AfterEach
    void clearInterruptFlag() {
        // The interrupt tests leave the flag set on purpose; it must not leak into the next test on this thread.
        Thread.interrupted();
    }

    @Test
    void backoffGrowsExponentiallyAndCaps() {
        // The retry schedule operators read in the docs; a wrong shift or cap would change it silently.
        assertThat(OutboxMessageSender.backoffMillis(0, 1000, 60000)).isEqualTo(1000);
        assertThat(OutboxMessageSender.backoffMillis(1, 1000, 60000)).isEqualTo(2000);
        assertThat(OutboxMessageSender.backoffMillis(2, 1000, 60000)).isEqualTo(4000);
        assertThat(OutboxMessageSender.backoffMillis(5, 1000, 60000)).isEqualTo(32000);
        assertThat(OutboxMessageSender.backoffMillis(6, 1000, 60000)).isEqualTo(60000);
        assertThat(OutboxMessageSender.backoffMillis(10, 1000, 60000)).isEqualTo(60000);
        assertThat(OutboxMessageSender.backoffMillis(1000, 1000, 60000)).isEqualTo(60000);
    }

    @Test
    void aShiftThatWrapsToZeroStillGetsTheCappedDelay() {
        // 1000 << 61 and 1000 << 62 overflow to exactly 0, which a check for a negative result misses: those retries
        // would run with no delay at all.
        assertThat(OutboxMessageSender.backoffMillis(61, 1000, 60000)).isEqualTo(60000);
        assertThat(OutboxMessageSender.backoffMillis(62, 1000, 60000)).isEqualTo(60000);
    }

    @Test
    void aSynchronousSendFailureIsCountedAsAFailedAttempt() {
        // KafkaTemplate rethrows a failure to build the producer raw. Uncaught, it left the row in PROCESSING with
        // its retry count unchanged, so the reaper returned it forever and it never reached FAILED.
        givenClaimedEvent(pendingEvent(0));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(
                new KafkaException("Failed to construct kafka producer")
        );

        assertThatThrownBy(() -> sender.processEvent(1L)).isInstanceOf(OutboxMessageProcessingException.class);

        verify(repository).scheduleRetry(
                eq(1L),
                any(Instant.class),
                eq("org.apache.kafka.common.KafkaException: Failed to construct kafka producer"),
                any(Instant.class)
        );
        verify(repository, never()).markCompleted(any(), any(), any());
    }

    @Test
    void aFailedSendSchedulesTheRetryAfterTheBackoff() {
        // A retry time in the past would have the poller resend at once, in a tight loop while the broker is down.
        givenClaimedEvent(pendingEvent(0));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("kafka down"))
        );
        Instant before = Instant.now();

        assertThatThrownBy(() -> sender.processEvent(1L)).isInstanceOf(OutboxMessageProcessingException.class);

        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).scheduleRetry(eq(1L), any(Instant.class), anyString(), nextAttempt.capture());
        assertThat(nextAttempt.getValue()).isAfter(before);
        verify(repository, never()).markFailed(any(), any(), any());
    }

    @Test
    void theLastAllowedAttemptMarksTheRowFailed() {
        // max-retries counts attempts, the first send included: the third failure of three is the dead letter.
        givenClaimedEvent(pendingEvent(2));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("kafka down"))
        );

        assertThatThrownBy(() -> sender.processEvent(1L))
                .isInstanceOf(OutboxMessageProcessingException.class)
                .hasMessageContaining("Attempt 3 of 3")
                .hasMessageContaining("marked FAILED");

        verify(repository).markFailed(eq(1L), any(Instant.class), eq("java.lang.IllegalStateException: kafka down"));
        verify(repository, never()).scheduleRetry(any(), any(), any(), any());
    }

    @Test
    void theStoredErrorNamesTheKafkaRootCauseAndNotOnlyTheWrapper() {
        // "KafkaProducerException: Failed to send" alone gives an operator nothing to act on before a requeue.
        givenClaimedEvent(pendingEvent(0));
        ProducerRecord<String, String> failed = new ProducerRecord<>(KafkaConstants.PAYMENT_EVENTS_TOPIC, "v");
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new KafkaProducerException(failed, "Failed to send", new TimeoutException("Topic not in metadata"))
        ));

        assertThatThrownBy(() -> sender.processEvent(1L)).isInstanceOf(OutboxMessageProcessingException.class);

        verify(repository).scheduleRetry(
                eq(1L),
                any(Instant.class),
                eq("org.springframework.kafka.core.KafkaProducerException: Failed to send"
                        + " <- org.apache.kafka.common.errors.TimeoutException: Topic not in metadata"),
                any(Instant.class)
        );
    }

    @Test
    void theCauseChainSkipsTheExecutionWrapperAndMessagesThatRepeatTheCause() {
        // Both only repeat the next link, and the column is read by a person deciding whether to requeue.
        IllegalStateException root = new IllegalStateException("broker gone");
        Throwable failure = new ExecutionException(new RuntimeException(root));

        assertThat(OutboxMessageSender.describeCauseChain(failure))
                .isEqualTo("java.lang.RuntimeException <- java.lang.IllegalStateException: broker gone");
    }

    @Test
    void aDatabaseErrorAfterADeliveredSendIsNotCountedAsAnAttempt() {
        // The broker already has the record. Counting it would push a delivered event toward FAILED; the row stays
        // in PROCESSING for the reaper instead.
        givenClaimedEvent(pendingEvent(0));
        givenSendSucceeds();
        when(repository.markCompleted(eq(1L), any(), any())).thenThrow(new DataAccessResourceFailureException("db"));

        assertThatThrownBy(() -> sender.processEvent(1L)).isInstanceOf(DataAccessResourceFailureException.class);

        verify(repository, never()).scheduleRetry(any(), any(), any(), any());
        verify(repository, never()).markFailed(any(), any(), any());
    }

    @Test
    void anInterruptedWaitReturnsTheRowWithoutCountingAnAttempt() throws Exception {
        // A shutdown during a slow send is not the event's fault; counting it would push rows toward FAILED on
        // every restart.
        givenClaimedEvent(pendingEvent(0));
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get()).thenThrow(new InterruptedException());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
        when(repository.releaseClaim(eq(1L), any())).thenReturn(1);

        sender.processEvent(1L);

        verify(repository).releaseClaim(eq(1L), any(Instant.class));
        verify(repository, never()).scheduleRetry(any(), any(), any(), any());
        verify(repository, never()).markFailed(any(), any(), any());
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void anInterruptRaisedInsideTheProducerIsNotCountedEither() {
        // kafka-clients throws its own unchecked InterruptException from a blocked send, not InterruptedException.
        givenClaimedEvent(pendingEvent(0));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            throw new InterruptException("interrupted while waiting for metadata");
        });

        sender.processEvent(1L);

        verify(repository).releaseClaim(eq(1L), any(Instant.class));
        verify(repository, never()).scheduleRetry(any(), any(), any(), any());
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void everyUpdateAfterTheClaimMatchesTheClaimTime() {
        // The claim time is the ownership token. An update matched on the id alone could complete or fail a row
        // that a startup reset handed to another instance.
        givenClaimedEvent(pendingEvent(0));
        givenSendSucceeds();
        when(repository.markCompleted(eq(1L), any(), any())).thenReturn(1);

        sender.processEvent(1L);

        ArgumentCaptor<Instant> claimedAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).claim(eq(1L), claimedAt.capture());
        verify(repository).markCompleted(eq(1L), eq(claimedAt.getValue()), any(Instant.class));
        assertThat(claimedAt.getValue().getNano() % 1000).isZero();
    }

    @Test
    void aFailureAfterTheClaimWasLostSaysTheAttemptWasNotRecorded() {
        // The row now belongs to another sender, so this sender's failure must not be reported as a retry.
        givenClaimedEvent(pendingEvent(0));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("kafka down"))
        );
        when(repository.scheduleRetry(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> sender.processEvent(1L))
                .isInstanceOf(OutboxMessageProcessingException.class)
                .hasMessageContaining("not recorded");
    }

    @Test
    void publishedRecordCarriesEventTypeHeaderAndTransactionReferenceKey() {
        // The wallet dispatches on the header and Kafka orders by the key; losing either breaks the consumer.
        givenClaimedEvent(pendingEvent(0));
        givenSendSucceeds();

        sender.processEvent(1L);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        ProducerRecord<String, String> record = sent.getValue();

        assertThat(record.topic()).isEqualTo(KafkaConstants.PAYMENT_EVENTS_TOPIC);
        assertThat(record.key()).isEqualTo("ref-1");
        assertThat(record.headers().lastHeader(KafkaConstants.HEADER_EVENT_TYPE))
                .isNotNull()
                .extracting(h -> new String(h.value(), StandardCharsets.UTF_8))
                .isEqualTo(KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED);
    }

    @Test
    void skipsWhenTheClaimIsNotAcquired() {
        // Another sender owns the row; sending it here as well would duplicate every fast-path event.
        when(repository.claim(eq(1L), any())).thenReturn(0);

        sender.processEvent(1L);

        verify(repository, never()).findById(any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    private void givenClaimedEvent(OutboxEvent event) {
        when(repository.claim(eq(1L), any())).thenReturn(1);
        when(repository.findById(1L)).thenReturn(Optional.of(event));
        when(repository.scheduleRetry(any(), any(), any(), any())).thenReturn(1);
        when(repository.markFailed(any(), any(), any())).thenReturn(1);
    }

    private void givenSendSucceeds() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.completedFuture(mock(SendResult.class))
        );
    }

    private OutboxEvent pendingEvent(int retryCount) {
        return OutboxEvent.builder()
                .id(1L)
                .aggregateType("PaymentTransaction")
                .aggregateId("ref-1")
                .eventType(KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED)
                .payload("{}")
                .retryCount(retryCount)
                .build();
    }
}
