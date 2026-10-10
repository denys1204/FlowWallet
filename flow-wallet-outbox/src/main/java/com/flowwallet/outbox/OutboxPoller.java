package com.flowwallet.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class OutboxPoller {
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMessageSender outboxMessageSender;
    private final OutboxProperties outboxProperties;

    /**
     * Returns every PROCESSING row to PENDING at startup, with no age threshold. This can resend a row that is
     * still in flight: one another instance is sending in a rolling deploy, or one this instance's poller or fast
     * path claimed, since scheduling and the web server start before ApplicationReadyEvent. Every copy carries the
     * same eventId.
     * See docs/adr/0008-transactional-outbox.md.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resetStuckEvents() {
        int resetCount = outboxEventRepository.resetAllProcessingToPending();

        if (resetCount > 0) {
            log.info("Reset {} stuck outbox events from PROCESSING to PENDING on startup", resetCount);
        }
    }

    /**
     * The jobs below run only where the service enables scheduling. Without them the fast path still sends most
     * rows, so nothing looks wrong, but a row whose first send failed stays PENDING for good and never reaches the
     * FAILED count that {@code outbox.events.failed} reports.
     * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnWhenNotScheduled(ApplicationReadyEvent event) {
        boolean scheduled = event.getApplicationContext()
                .containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
        if (!scheduled) {
            log.warn(
                    "Scheduling is off, so the outbox poller, reaper and cleanup do not run; a row the fast path "
                            + "fails to send is never retried"
            );
        }
    }

    /**
     * The threshold has to stay well above the longest single send (bounded by the producer's max.block.ms and
     * delivery.timeout.ms), or live sends are reset and go out twice.
     */
    @Scheduled(fixedDelayString = "${outbox.reaper-interval-ms:60000}")
    public void reapStuckProcessing() {
        Instant threshold = Instant.now().minusMillis(outboxProperties.getStuckProcessingThresholdMs());

        int reaped = outboxEventRepository.resetProcessingClaimedBefore(threshold);

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
                Instant.now(),
                PageRequest.of(0, outboxProperties.getBatchSize())
        );

        if (events.isEmpty()) {
            return;
        }

        log.debug("Fallback Poller: Found {} unprocessed outbox events", events.size());

        for (OutboxEvent event : events) {
            // An interrupt means shutdown. Claiming another row now would only start a send that cannot finish.
            if (Thread.currentThread().isInterrupted()) {
                log.info("Fallback Poller: interrupted, leaving the remaining outbox events for the next run");
                return;
            }
            try {
                outboxMessageSender.processEvent(event.getId());
            } catch (RuntimeException e) {
                // Skip it, so one failing row cannot block other aggregates' events. Send order per aggregate is
                // therefore not guaranteed. See docs/adr/0008-transactional-outbox.md.
                log.error("Fallback Poller: outbox event {} was not sent; moving on to the next", event.getId(), e);
            }
        }
    }

    @Scheduled(cron = "${outbox.cleanup-cron:0 0 3 * * *}")
    public void cleanupOldEvents() {
        Instant cutoff = Instant.now().minus(outboxProperties.getRetentionDays(), ChronoUnit.DAYS);

        // COMPLETED only. A FAILED row is the only record of an event that never reached Kafka, so it stays until
        // an operator requeues it.
        int deleted = outboxEventRepository.deleteCompletedBefore(cutoff);

        if (deleted > 0) {
            log.info(
                    "Cleaned up {} completed outbox events (older than {} days)",
                    deleted,
                    outboxProperties.getRetentionDays()
            );
        }
    }
}
