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
     * Maximum number of retries before marking an event as FAILED.
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
     * Number of days to retain COMPLETED and FAILED outbox events before cleanup.
     */
    private int retentionDays = 7;

    /**
     * How long (ms) an event may stay in PROCESSING before the reaper assumes the sender crashed and
     * returns it to PENDING. MUST be comfortably larger than the longest possible single send, otherwise
     * a live in-flight send could be reset and re-published.
     */
    private long stuckProcessingThresholdMs = 300000;
}
