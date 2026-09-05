package com.flowwallet.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Four of these keys were absent from application.yml for a while, which meant the environment
 * variables documented for them did nothing at all — Spring binds what it recognises and says nothing
 * about the rest. A name that stops matching its field fails the same silent way, so it is worth a test
 * rather than a careful reading.
 */
class OutboxPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(OutboxProperties.class);

    @Test
    @DisplayName("every documented key reaches its field")
    void everyKeyBinds() {
        runner.withPropertyValues(
                "outbox.batch-size=7",
                "outbox.max-retries=9",
                "outbox.retention-days=11",
                "outbox.retry-backoff-base-ms=1234",
                "outbox.retry-backoff-max-ms=5678",
                "outbox.stuck-processing-threshold-ms=91011"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            OutboxProperties outbox = context.getBean(OutboxProperties.class);

            assertThat(outbox.getBatchSize()).isEqualTo(7);
            assertThat(outbox.getMaxRetries()).isEqualTo(9);
            assertThat(outbox.getRetentionDays()).isEqualTo(11);
            assertThat(outbox.getRetryBackoffBaseMs()).isEqualTo(1234);
            assertThat(outbox.getRetryBackoffMaxMs()).isEqualTo(5678);
            assertThat(outbox.getStuckProcessingThresholdMs()).isEqualTo(91011);
        });
    }

    @Test
    @DisplayName("the shipped defaults are what the code assumes when nothing is set")
    void defaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            OutboxProperties outbox = context.getBean(OutboxProperties.class);

            assertThat(outbox.getBatchSize()).isEqualTo(50);
            assertThat(outbox.getMaxRetries()).isEqualTo(3);
            assertThat(outbox.getRetentionDays()).isEqualTo(7);
            assertThat(outbox.getRetryBackoffBaseMs()).isEqualTo(1000);
            assertThat(outbox.getRetryBackoffMaxMs()).isEqualTo(60000);
            assertThat(outbox.getStuckProcessingThresholdMs()).isEqualTo(300000);
        });
    }
}
