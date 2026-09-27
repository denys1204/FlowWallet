package com.flowwallet.payment.outbox;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxMessageSender {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties outboxProperties;

    /**
     * Claims the row, sends it and records the outcome. A failed attempt is recorded and thrown as
     * {@link OutboxMessageProcessingException} for the caller to log. An interrupt counts no attempt: the row goes
     * back to PENDING and the method returns with the thread's interrupt flag set.
     * See docs/adr/0018-outbox-sends-own-their-claim.md.
     */
    public void processEvent(Long outboxEventId) {
        // processing_started_at is a Postgres TIMESTAMP with microsecond precision. Truncating here keeps the
        // value later updates match on equal to the stored one.
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (outboxEventRepository.claim(outboxEventId, claimedAt) == 0) {
            log.debug("Outbox event {} is not PENDING or another sender claimed it. Skipping.", outboxEventId);
            return;
        }

        OutboxEvent event = outboxEventRepository.findById(outboxEventId).orElseThrow(
                () -> new OutboxMessageProcessingException("Outbox event " + outboxEventId + " not found after claim")
        );

        try {
            send(event);
        } catch (InterruptedException | InterruptException e) {
            releaseAfterInterrupt(outboxEventId, claimedAt);
            return;
        } catch (ExecutionException | RuntimeException e) {
            // KafkaTemplate reports an interrupted send as a KafkaException and sets the flag again.
            if (Thread.currentThread().isInterrupted()) {
                releaseAfterInterrupt(outboxEventId, claimedAt);
                return;
            }
            throw recordFailedAttempt(event, claimedAt, e);
        }

        // Outside the catch: a database error here comes after the broker took the record, so it is not a failed
        // attempt. It propagates and leaves the row in PROCESSING for the reaper, which sends it again.
        if (outboxEventRepository.markCompleted(outboxEventId, claimedAt, Instant.now()) == 0) {
            log.warn(
                    "Outbox event {} was sent after its claim was reset; another sender may send it again",
                    outboxEventId
            );
            return;
        }
        log.debug("Sent outbox event {} to Kafka", outboxEventId);
    }

    private void send(OutboxEvent event) throws InterruptedException, ExecutionException {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaConstants.PAYMENT_EVENTS_TOPIC,
                event.getAggregateId(),
                event.getPayload()
        );
        record.headers().add(KafkaConstants.HEADER_EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record).get();
    }

    /**
     * Counts the attempt: a retry after the backoff, or FAILED once {@code max-retries} attempts have failed. The
     * error is stored as its cause chain, because the wrapper's own message rarely names the Kafka cause.
     * See docs/adr/0008-transactional-outbox.md.
     */
    private OutboxMessageProcessingException recordFailedAttempt(
            OutboxEvent event,
            Instant claimedAt,
            Exception failure
    ) {
        String error = describeCauseChain(failure);
        int attempt = event.getRetryCount() + 1;
        int maxAttempts = outboxProperties.getMaxRetries();
        boolean lastAttempt = attempt >= maxAttempts;

        int updated;
        if (lastAttempt) {
            updated = outboxEventRepository.markFailed(event.getId(), claimedAt, error);
        } else {
            Instant nextAttemptAt = Instant.now().plusMillis(backoffMillis(
                    event.getRetryCount(),
                    outboxProperties.getRetryBackoffBaseMs(),
                    outboxProperties.getRetryBackoffMaxMs()
            ));
            updated = outboxEventRepository.scheduleRetry(event.getId(), claimedAt, error, nextAttemptAt);
        }

        String outcome = updated == 0
                ? "not recorded, its claim was reset meanwhile"
                : lastAttempt ? "marked FAILED" : "retry scheduled";
        return new OutboxMessageProcessingException(
                "Attempt " + attempt + " of " + maxAttempts + " to send outbox event " + event.getId()
                        + " failed (" + outcome + "): " + error,
                failure
        );
    }

    /**
     * An interrupt is a shutdown, not a verdict on the event. The flag is cleared for the update, because the
     * connection pool refuses an interrupted thread that has to wait for a connection, and set again afterwards
     * so the caller stops. If the update fails, the reaper returns the row.
     */
    private void releaseAfterInterrupt(Long outboxEventId, Instant claimedAt) {
        Thread.interrupted();
        try {
            if (outboxEventRepository.releaseClaim(outboxEventId, claimedAt) == 1) {
                log.info("Send of outbox event {} was interrupted; returned it to PENDING", outboxEventId);
            }
        } catch (RuntimeException e) {
            log.warn("Send of outbox event {} was interrupted; the reaper will return it", outboxEventId, e);
        } finally {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Renders {@code Class: message <- Class: message ...} from the outermost exception down to the root cause,
     * leaving out an {@link ExecutionException} wrapper and a message that only repeats the cause.
     */
    static String describeCauseChain(Throwable failure) {
        Throwable current = failure instanceof ExecutionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        StringJoiner chain = new StringJoiner(" <- ");
        while (current != null && seen.add(current)) {
            Throwable cause = current.getCause();
            String message = current.getMessage();
            boolean repeatsCause = cause != null && cause.toString().equals(message);
            chain.add(message == null || repeatsCause
                    ? current.getClass().getName()
                    : current.getClass().getName() + ": " + message);
            current = cause;
        }
        return chain.toString();
    }

    /**
     * Exponential backoff (ms) for the given retry attempt: {@code baseMs * 2^retryCount}, capped at
     * {@code maxMs}. The cap is checked before shifting, because a large shift can overflow to a negative value
     * or to 0.
     */
    static long backoffMillis(int retryCount, long baseMs, long maxMs) {
        if (retryCount < 0) {
            return baseMs;
        }
        if (retryCount >= Long.SIZE - 1 || baseMs > (maxMs >> retryCount)) {
            return maxMs;
        }
        return baseMs << retryCount;
    }
}
