package com.flowwallet.payment.config;

import com.flowwallet.payment.outbox.OutboxOperations;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * The alert signal for FAILED outbox rows, which nothing retries until an operator requeues them.
 * See docs/adr/0008-transactional-outbox.md.
 */
@Configuration
public class OutboxMetricsConfig {
    public OutboxMetricsConfig(MeterRegistry registry, OutboxOperations operations) {
        Gauge.builder("outbox.events.failed", operations, OutboxOperations::failedCount)
                .description("Number of outbox events currently in FAILED status (exhausted retries)")
                .register(registry);
    }
}
