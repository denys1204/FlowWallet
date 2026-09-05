package com.flowwallet.payment.outbox;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxMessageSender {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OutboxProperties outboxProperties;

    public void processEvent(Long eventId) {
        int updated = outboxEventRepository.lockForProcessing(
                eventId,
                OutboxStatus.PROCESSING,
                OutboxStatus.PENDING,
                Instant.now()
        );

        if (updated == 0) {
            log.debug("OutboxEvent {} is already being processed or is not in PENDING status. Skipping.", eventId);
            return;
        }

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow(() -> {
            log.error("OutboxEvent {} not found after successful lock. Possible data integrity issue.", eventId);
            return new OutboxMessageProcessingException("OutboxEvent not found after lock: " + eventId);
        });

        try {
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    KafkaConstants.PAYMENT_EVENTS_TOPIC,
                    event.getAggregateId(),
                    event.getPayload()
            );
            record.headers().add(
                    KafkaConstants.HEADER_EVENT_TYPE,
                    event.getEventType().getBytes(StandardCharsets.UTF_8)
            );

            kafkaTemplate.send(record).get();

            outboxEventRepository.markAsCompleted(event.getId(), OutboxStatus.COMPLETED, Instant.now());
            log.debug("Successfully sent outbox event {} to Kafka", event.getId());
        } catch (ExecutionException | KafkaException e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error during Kafka send";
            throw recordSendFailure(event, errorMessage, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw recordSendFailure(event, "Thread interrupted", e);
        }
    }

    /**
     * Records a failed send: increments the retry count (or marks FAILED once maxRetries is reached) and
     * returns the exception to throw so the caller can retry later. The error message is stored as-is —
     * the {@code error_message} column is TEXT, so no truncation is applied.
     */
    private OutboxMessageProcessingException recordSendFailure(
            OutboxEvent event,
            String errorMessage,
            Throwable cause
    ) {
        log.error("Failed to process outbox event {}. Incrementing retry count.", event.getId(), cause);

        Instant nextAttemptAt = Instant.now().plusMillis(backoffMillis(
                event.getRetryCount(),
                outboxProperties.getRetryBackoffBaseMs(),
                outboxProperties.getRetryBackoffMaxMs()
        ));

        outboxEventRepository.incrementRetryOrFail(
                event.getId(),
                errorMessage,
                outboxProperties.getMaxRetries(),
                OutboxStatus.FAILED,
                OutboxStatus.PENDING,
                nextAttemptAt
        );

        return new OutboxMessageProcessingException("Failed to send outbox event to Kafka", cause);
    }

    /**
     * Exponential backoff (ms) for the given retry attempt: {@code baseMs * 2^retryCount}, capped at
     * {@code maxMs}. Overflow (very large {@code retryCount}) also clamps to {@code maxMs}.
     */
    static long backoffMillis(int retryCount, long baseMs, long maxMs) {
        if (retryCount < 0) {
            return baseMs;
        }
        if (retryCount >= Long.SIZE - 1) {
            return maxMs;
        }
        long delay = baseMs << retryCount;
        return (delay < 0 || delay > maxMs) ? maxMs : delay;
    }
}
