package com.flowwallet.payment.config;

import com.flowwallet.payment.outbox.OutboxOperations;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the FAILED outbox backlog as a Micrometer gauge ({@code outbox.events.failed}) so it is
 * visible via Actuator/metrics and can be alerted on (see Phase 5 observability).
 */
@Configuration
public class OutboxMetricsConfig {
    public OutboxMetricsConfig(MeterRegistry registry, OutboxOperations operations) {
        Gauge.builder("outbox.events.failed", operations, OutboxOperations::failedCount)
                .description("Number of outbox events currently in FAILED status (exhausted retries)")
                .register(registry);
    }
}
