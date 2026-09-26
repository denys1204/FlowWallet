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

    /**
     * Puts every PROCESSING row back to PENDING when the service starts, with no age threshold.
     * <p>
     * Right for one instance: nothing can be mid-send at startup, and rows a crashed sender left behind go
     * out immediately instead of waiting for the reaper's threshold. With several instances -- a rolling
     * deploy overlaps old and new -- a starting instance can reset a row another instance is still sending,
     * and that event goes to Kafka twice. That is accepted deliberately: the payload is stored verbatim, so
     * both copies carry the same eventId and the wallet's barrier discards the second. The cost is one extra
     * message; the alternative, reaping with the threshold, would delay every post-crash recovery by it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resetStuckEvents() {
        int resetCount = outboxEventRepository.resetStuckEvents(OutboxStatus.PENDING, OutboxStatus.PROCESSING);

        if (resetCount > 0) {
            log.info("Reset {} stuck outbox events from PROCESSING to PENDING on startup", resetCount);
        }
    }

    /**
     * Recovers events left in PROCESSING by a sender that died mid-send (the startup reset only runs once).
     * The threshold must stay well above the longest possible single send so a live in-flight send is never reset.
     */
    @Scheduled(fixedDelayString = "${outbox.reaper-interval-ms:60000}")
    public void reapStuckProcessing() {
        Instant threshold = Instant.now().minusMillis(outboxProperties.getStuckProcessingThresholdMs());

        int reaped = outboxEventRepository.resetStuckProcessing(
                OutboxStatus.PENDING,
                OutboxStatus.PROCESSING,
                threshold
        );

        if (reaped > 0) {
            log.warn(
                    "Reaped {} outbox events stuck in PROCESSING longer than {} ms back to PENDING",
                    reaped,
                    outboxProperties.getStuckProcessingThresholdMs()
            );
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
                // transactions' events. The price is that send order within one transaction is NOT
                // guaranteed -- a failure waiting out its backoff can be overtaken by a later success for
                // the same reference. The partition key keeps messages that ARE sent in order; it cannot
                // order messages that have not been sent yet. The wallet does not depend on order: a failure
                // moves no money.
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

        // COMPLETED only. A FAILED row is an event that never reached Kafka -- for a completed payment, a
        // credit that never happened -- and it is the only record of it. Deleting it on a timer would make
        // the money disappear without a trace, so it stays until someone requeues it.
        int deleted = outboxEventRepository.deleteOldEvents(
                List.of(OutboxStatus.COMPLETED),
                cutoff
        );

        if (deleted > 0) {
            log.info(
                    "Cleaned up {} completed outbox events (older than {} days)",
                    deleted,
                    outboxProperties.getRetentionDays()
            );
        }
    }
}
