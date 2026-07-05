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
     * Polling interval in milliseconds.
     */
    private long pollIntervalMs = 10000;

    /**
     * Maximum number of retries before marking an event as FAILED.
     */
    private int maxRetries = 3;
}
