package com.flowwallet.payment.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {
    /**
     * Number of outbox events to fetch and process in one polling batch.
     */
    @Min(value = 1, message = "outbox.batch-size must be at least 1")
    private int batchSize = 50;

    /**
     * Maximum number of send attempts, the first send included, before the event is marked FAILED. With the
     * default backoff, 10 attempts spend about four minutes waiting between sends, so a short broker outage does
     * not dead-letter events.
     */
    @Min(value = 1, message = "outbox.max-retries must be at least 1")
    private int maxRetries = 10;

    /**
     * Base delay (ms) for the exponential retry backoff: attempt n waits base * 2^n, capped at the max.
     */
    @Min(value = 1, message = "outbox.retry-backoff-base-ms must be at least 1")
    private long retryBackoffBaseMs = 1000;

    /**
     * Upper bound (ms) for the exponential retry backoff delay.
     */
    @Min(value = 1, message = "outbox.retry-backoff-max-ms must be at least 1")
    private long retryBackoffMaxMs = 60000;

    /**
     * Number of days to retain COMPLETED outbox events before cleanup. FAILED events are never deleted by
     * age. See docs/adr/0008-transactional-outbox.md.
     */
    @Min(value = 1, message = "outbox.retention-days must be at least 1")
    private int retentionDays = 7;

    /**
     * How long (ms) an event may stay in PROCESSING before the reaper returns it to PENDING. It must stay well
     * above the longest single send, or a live send is reset and published twice.
     */
    @Min(value = 1, message = "outbox.stuck-processing-threshold-ms must be at least 1")
    private long stuckProcessingThresholdMs = 300000;

    @AssertTrue(message = "outbox.retry-backoff-base-ms must not exceed outbox.retry-backoff-max-ms")
    public boolean isBackoffRangeOrdered() {
        return retryBackoffBaseMs <= retryBackoffMaxMs;
    }
}
