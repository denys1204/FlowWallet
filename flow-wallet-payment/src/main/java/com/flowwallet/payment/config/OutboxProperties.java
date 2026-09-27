package com.flowwallet.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {
    /**
     * Number of outbox events to fetch and process in one polling batch.
     */
    private int batchSize = 50;

    /**
     * Maximum number of send attempts, the first send included, before the event is marked FAILED; 3 means one
     * send and two retries.
     */
    private int maxRetries = 3;

    /**
     * Base delay (ms) for the exponential retry backoff: attempt n waits base * 2^n, capped at the max.
     */
    private long retryBackoffBaseMs = 1000;

    /**
     * Upper bound (ms) for the exponential retry backoff delay.
     */
    private long retryBackoffMaxMs = 60000;

    /**
     * Number of days to retain COMPLETED outbox events before cleanup. FAILED events are never deleted by
     * age. See docs/adr/0008-transactional-outbox.md.
     */
    private int retentionDays = 7;

    /**
     * How long (ms) an event may stay in PROCESSING before the reaper returns it to PENDING. It must stay well
     * above the longest single send, or a live send is reset and published twice.
     */
    private long stuckProcessingThresholdMs = 300000;
}
