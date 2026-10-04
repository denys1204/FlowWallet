package com.flowwallet.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on every scheduled job in the service: the outbox poller, reaper and cleanup, and the reconciler.
 * {@code payment.scheduling.enabled=false} turns them all off, so a test that drives those steps itself is not raced
 * by a background run.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payment.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
