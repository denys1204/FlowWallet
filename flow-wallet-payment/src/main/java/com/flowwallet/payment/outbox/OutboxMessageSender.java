package com.flowwallet.payment.outbox;

import com.flowwallet.common.constant.KafkaConstants;
import com.flowwallet.payment.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxMessageSender {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OutboxProperties outboxProperties;

    public void processEvent(Long eventId) {
        int updated = outboxEventRepository.lockForProcessing(eventId, OutboxStatus.PROCESSING, OutboxStatus.PENDING);
        if (updated == 0) {
            log.debug("OutboxEvent {} is already being processed or is not in PENDING status. Skipping.", eventId);
            return;
        }

        outboxEventRepository.findById(eventId).ifPresent(event -> {
            try {
                kafkaTemplate.send(
                        KafkaConstants.PAYMENT_EVENTS_TOPIC,
                        event.getAggregateId(),
                        event.getPayload()
                ).get();

                outboxEventRepository.markAsCompleted(event.getId(), OutboxStatus.COMPLETED, Instant.now());
                log.debug("Successfully sent outbox event {} to Kafka", event.getId());
            } catch (java.util.concurrent.ExecutionException | org.springframework.kafka.KafkaException e) {
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error during Kafka send";
                if (errorMessage.length() > 255) {
                    errorMessage = errorMessage.substring(0, 255);
                }
                
                log.error("Failed to process outbox event {}. Incrementing retry count.", event.getId(), e);
                outboxEventRepository.incrementRetryOrFail(event.getId(), errorMessage, outboxProperties.getMaxRetries(), OutboxStatus.FAILED, OutboxStatus.PENDING);
                
                // Throw custom exception so the poller breaks the loop
                throw new OutboxMessageProcessingException("Failed to send outbox event to Kafka", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                
                log.error("Thread interrupted while sending outbox event {}. Incrementing retry count.", event.getId(), e);
                outboxEventRepository.incrementRetryOrFail(event.getId(), "Thread interrupted", outboxProperties.getMaxRetries(), OutboxStatus.FAILED, OutboxStatus.PENDING);
                
                throw new OutboxMessageProcessingException("Thread interrupted during Kafka send", e);
            }
        });
    }
}
