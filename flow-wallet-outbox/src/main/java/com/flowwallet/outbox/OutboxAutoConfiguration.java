package com.flowwallet.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * Sets up the outbox in every service that depends on {@code flow-wallet-outbox}. Listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * <p>
 * {@code @AutoConfigurationPackage} adds this package to the service's entity and repository scan, so it has to run
 * before the JPA auto-configurations read the packages; {@code @EntityScan} would replace the service's own scan
 * instead. Async is on, so the send after the commit stays off the request thread, where it would wait on the
 * broker. The poller's jobs run only where the service enables scheduling, and the poller warns at startup where it
 * does not. The service declares the {@link OutboxTopic}.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@AutoConfigurationPackage
@EnableConfigurationProperties(OutboxProperties.class)
@AutoConfiguration(before = {HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
@Import({
        Outbox.class,
        OutboxMessageSender.class,
        OutboxEventListener.class,
        OutboxPoller.class,
        OutboxOperations.class,
        OutboxEndpoint.class
})
public class OutboxAutoConfiguration {
    /**
     * Turns async on unless the service has already done so. A second {@code @EnableAsync} here would take over the
     * processor and reset the service's own attributes, such as its order, to the defaults.
     */
    @EnableAsync
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(name = TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME)
    static class AsyncConfiguration {
    }

    /**
     * The alert signal for FAILED outbox rows, which nothing retries until an operator requeues them.
     * See docs/adr/0008-transactional-outbox.md.
     */
    @Bean
    public MeterBinder outboxFailedEventsGauge(OutboxOperations operations) {
        return registry -> Gauge.builder("outbox.events.failed", operations, OutboxOperations::failedCount)
                .description("Number of outbox events currently in FAILED status (exhausted retries)")
                .register(registry);
    }
}
