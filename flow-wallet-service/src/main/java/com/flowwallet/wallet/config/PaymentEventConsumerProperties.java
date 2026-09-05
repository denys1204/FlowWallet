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
     * Redeliveries after the first attempt. These exist for failures that a later attempt can actually
     * resolve — two events racing to open one wallet, a momentary database blip. A record that fails
     * deterministically will exhaust them and be dead-lettered, which is the intended end.
     */
    @Min(value = 0, message = "wallet.consumer.retry.max-attempts must not be negative")
    private int maxAttempts = 3;

    @Min(value = 1, message = "wallet.consumer.retry.initial-interval-ms must be at least 1")
    private long initialIntervalMs = 500;

    @Min(value = 1, message = "wallet.consumer.retry.max-interval-ms must be at least 1")
    private long maxIntervalMs = 10_000;

    private double multiplier = 2.0;

    /**
     * A backoff that shrinks would make each retry more aggressive than the last, which is the opposite of
     * what backoff is for and is easiest to introduce by typing the multiplier wrong.
     */
    @AssertTrue(message = "wallet.consumer.retry.multiplier must be at least 1.0")
    public boolean isMultiplierNonShrinking() {
        return multiplier >= 1.0;
    }

    @AssertTrue(message = "wallet.consumer.retry.initial-interval-ms must not exceed max-interval-ms")
    public boolean isIntervalRangeOrdered() {
        return initialIntervalMs <= maxIntervalMs;
    }
}
