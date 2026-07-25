package com.flowwallet.payment.outbox;

import com.flowwallet.payment.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessageSender outboxMessageSender;
    private final OutboxProperties outboxProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void resetStuckEvents() {
        int resetCount = outboxEventRepository.resetStuckEvents(OutboxStatus.PENDING, OutboxStatus.PROCESSING);

        if (resetCount > 0) {
            log.info("Reset {} stuck outbox events from PROCESSING to PENDING on startup", resetCount);
        }
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:10000}")
    public void pollOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findDispatchable(
                OutboxStatus.PENDING,
                Instant.now(),
                PageRequest.of(0, outboxProperties.getBatchSize())
        );

        if (events.isEmpty()) {
            return;
        }

        log.debug("Fallback Poller: Found {} unprocessed outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                outboxMessageSender.processEvent(event.getId());
            } catch (OutboxMessageProcessingException e) {
                // Skip this event and keep going: one failing event must not block delivery of unrelated
                // transactions' events. Per-key ordering is preserved by Kafka's partition key (aggregateId),
                // not by processing the batch strictly in order.
                log.error(
                        "Fallback Poller: Failed to process outbox event {}. Skipping; it will be retried next poll.",
                        event.getId(),
                        e
                );
            }
        }
    }

    @Scheduled(cron = "${outbox.cleanup-cron:0 0 3 * * *}")
    public void cleanupOldEvents() {
        Instant cutoff = Instant.now().minus(outboxProperties.getRetentionDays(), ChronoUnit.DAYS);

        int deleted = outboxEventRepository.deleteOldEvents(
                List.of(OutboxStatus.COMPLETED, OutboxStatus.FAILED),
                cutoff
        );

        if (deleted > 0) {
            log.info(
                    "Cleaned up {} old outbox events (older than {} days)",
                    deleted,
                    outboxProperties.getRetentionDays()
            );
        }
    }
}
