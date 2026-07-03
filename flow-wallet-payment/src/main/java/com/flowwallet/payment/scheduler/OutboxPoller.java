package com.flowwallet.payment.scheduler;

import com.flowwallet.common.constant.KafkaConstants;
import com.flowwallet.payment.entity.OutboxEvent;
import com.flowwallet.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${outbox.batch-size:50}")
    private int batchSize;

    @Transactional
    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    public void pollOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findByProcessedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, batchSize));

        if (events.isEmpty()) {
            return;
        }

        log.debug("Found {} unprocessed outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(
                        KafkaConstants.PAYMENT_EVENTS_TOPIC,
                        event.getAggregateId(),
                        event.getPayload()
                ).get();

                event.setProcessedAt(Instant.now());
                log.debug("Sent outbox event {} to Kafka", event.getId());
            } catch (Exception e) {
                log.error("Failed to send outbox event {} to Kafka", event.getId(), e);
            }
        }

        outboxEventRepository.saveAll(events);
    }
}
