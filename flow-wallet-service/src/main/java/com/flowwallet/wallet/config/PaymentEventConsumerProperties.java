package com.flowwallet.wallet.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * How hard the consumer tries before giving a record to the dead-letter topic.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "wallet.consumer.retry")
public class PaymentEventConsumerProperties {
    /**
     * The backoff sleeps on the consumer thread, which does not poll meanwhile. Kafka's default
     * {@code max.poll.interval.ms}, which this service keeps, is 300000; a pause near it makes the broker drop the
     * consumer from its group mid-retry and hand the partition to another. A fifth of it leaves room for the
     * records the same poll processed before the failure.
     */
    static final long MAX_INTERVAL_CEILING_MS = 60_000;

    /**
     * Redeliveries after the first attempt, for failures a later attempt can resolve, such as a momentary
     * database outage. A record that fails every time exhausts them and is dead-lettered.
     */
    @Min(value = 0, message = "wallet.consumer.retry.max-attempts must not be negative")
    private int maxAttempts = 3;

    @Min(value = 1, message = "wallet.consumer.retry.initial-interval-ms must be at least 1")
    private long initialIntervalMs = 500;

    @Min(value = 1, message = "wallet.consumer.retry.max-interval-ms must be at least 1")
    private long maxIntervalMs = 10_000;

    private double multiplier = 2.0;

    @AssertTrue(message = "wallet.consumer.retry.multiplier must be at least 1.0")
    public boolean isMultiplierNonShrinking() {
        return multiplier >= 1.0;
    }

    @AssertTrue(message = "wallet.consumer.retry.initial-interval-ms must not exceed max-interval-ms")
    public boolean isIntervalRangeOrdered() {
        return initialIntervalMs <= maxIntervalMs;
    }

    @AssertTrue(message = "wallet.consumer.retry.max-interval-ms must not exceed 60000 (max.poll.interval.ms / 5)")
    public boolean isMaxIntervalWellBelowPollInterval() {
        return maxIntervalMs <= MAX_INTERVAL_CEILING_MS;
    }
}
