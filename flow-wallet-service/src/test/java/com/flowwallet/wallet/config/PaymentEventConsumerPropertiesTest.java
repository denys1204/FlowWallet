package com.flowwallet.wallet.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds and validates the real class, so a check that stops running shows up here rather than as a consumer that
 * starts with a backoff it cannot survive.
 */
class PaymentEventConsumerPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PaymentEventConsumerProperties.class);

    @Test
    void theShippedDefaultsStartAndMatchTheDocumentedOnes() {
        // Guards a default that fails its own checks, which would stop every service start with no setting made.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PaymentEventConsumerProperties retry = context.getBean(PaymentEventConsumerProperties.class);

            assertThat(retry.getMaxAttempts()).isEqualTo(3);
            assertThat(retry.getInitialIntervalMs()).isEqualTo(500);
            assertThat(retry.getMaxIntervalMs()).isEqualTo(10_000);
            assertThat(retry.getMultiplier()).isEqualTo(2.0);
        });
    }

    @Test
    void aMaximumIntervalAboveTheCeilingFailsStartup() {
        // The backoff sleeps without polling. A pause near max.poll.interval.ms (300000 by default) makes the
        // broker rebalance the group mid-retry, so the partition moves while its record is still being retried.
        runner.withPropertyValues("wallet.consumer.retry.max-interval-ms=60001")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theCeilingItselfIsAccepted() {
        // Guards an off-by-one that would refuse the largest interval the check means to allow.
        runner.withPropertyValues("wallet.consumer.retry.max-interval-ms=60000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"initial-interval-ms", "max-interval-ms"})
    void anIntervalBelowOneMillisecondFailsStartup(String key) {
        // A zero interval retries at once, so every retry of a momentary outage lands inside the outage. The
        // message is checked because a zero maximum also breaks the ordering check.
        runner.withPropertyValues("wallet.consumer.retry." + key + "=0")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("wallet.consumer.retry." + key + " must be at least 1"));
    }

    @Test
    void aNegativeRetryCountFailsStartup() {
        // A negative number of redeliveries means nothing, and a typo for a real count should stop startup
        // rather than leave the retry budget to whatever the backoff makes of it.
        runner.withPropertyValues("wallet.consumer.retry.max-attempts=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aShrinkingMultiplierFailsStartup() {
        // Below 1.0 each wait is shorter than the last, so the retries would crowd into the first moments of an
        // outage they are meant to outlast.
        runner.withPropertyValues("wallet.consumer.retry.multiplier=0.5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anInitialIntervalAboveTheMaximumFailsStartup() {
        // The cap would win on the first retry, so the configured initial interval would never apply.
        runner.withPropertyValues(
                "wallet.consumer.retry.initial-interval-ms=20000",
                "wallet.consumer.retry.max-interval-ms=10000"
        ).run(context -> assertThat(context).hasFailed());
    }
}
