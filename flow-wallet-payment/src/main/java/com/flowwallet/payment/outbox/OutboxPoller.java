package com.flowwallet.payment.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessageSender outboxMessageSender;

    @Value("${outbox.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:5000}")
    public void pollOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findByProcessedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, batchSize));

        if (events.isEmpty()) {
            return;
        }

        log.debug("Fallback Poller: Found {} unprocessed outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                outboxMessageSender.processEvent(event.getId());
            } catch (Exception e) {
                log.error("Fallback Poller: Failed to process outbox event {}. Stopping batch to preserve order.", event.getId(), e);
                break;
            }
        }
    }
}
