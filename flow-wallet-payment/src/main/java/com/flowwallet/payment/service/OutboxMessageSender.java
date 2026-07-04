package com.flowwallet.payment.service;

import com.flowwallet.common.constant.KafkaConstants;
import com.flowwallet.payment.repository.OutboxEventRepository;
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

    public void processEvent(Long eventId) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            if (event.getProcessedAt() != null) {
                log.debug("OutboxEvent {} already processed. Skipping.", eventId);
                return;
            }

            try {
                kafkaTemplate.send(
                        KafkaConstants.PAYMENT_EVENTS_TOPIC,
                        event.getAggregateId(),
                        event.getPayload()
                ).get();

                event.setProcessedAt(Instant.now());
                outboxEventRepository.save(event);

                log.debug("Successfully sent outbox event {} to Kafka", event.getId());
            } catch (Exception e) {
                log.error("Failed to process outbox event {}. It will be retried later.", event.getId(), e);
                throw new RuntimeException("Failed to process outbox event", e); // Кидаємо далі, щоб поллер міг зробити break
            }
        });
    }
}
