package com.flowwallet.payment.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * How hard the withdrawal command consumer tries before giving a record to its dead-letter topic, with the same
 * bounds as the wallet's consumer. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "payment.consumer.retry")
public class WithdrawalCommandRetryProperties {
    /**
     * The backoff sleeps on the consumer thread, which does not poll meanwhile, so it stays well below Kafka's
     * {@code max.poll.interval.ms} of 300000.
     */
    static final long MAX_INTERVAL_CEILING_MS = 60_000;

    /**
     * Redeliveries after the first attempt, for failures a later attempt can resolve, such as a momentary database
     * outage. A record that fails every time exhausts them and is dead-lettered.
     */
    @Min(value = 0, message = "payment.consumer.retry.max-attempts must not be negative")
    private int maxAttempts = 3;

    @Min(value = 1, message = "payment.consumer.retry.initial-interval-ms must be at least 1")
    private long initialIntervalMs = 500;

    @Min(value = 1, message = "payment.consumer.retry.max-interval-ms must be at least 1")
    private long maxIntervalMs = 10_000;

    private double multiplier = 2.0;

    @AssertTrue(message = "payment.consumer.retry.multiplier must be at least 1.0")
    public boolean isMultiplierNonShrinking() {
        return multiplier >= 1.0;
    }

    @AssertTrue(message = "payment.consumer.retry.initial-interval-ms must not exceed max-interval-ms")
    public boolean isIntervalRangeOrdered() {
        return initialIntervalMs <= maxIntervalMs;
    }

    @AssertTrue(message = "payment.consumer.retry.max-interval-ms must not exceed 60000 (max.poll.interval.ms / 5)")
    public boolean isMaxIntervalWellBelowPollInterval() {
        return maxIntervalMs <= MAX_INTERVAL_CEILING_MS;
    }
}
