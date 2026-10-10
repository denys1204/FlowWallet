package com.flowwallet.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real class and builds the real topic from it, so a renamed key or a value that never reaches the
 * {@code NewTopic} shows up here rather than as a dead-letter topic created with the broker's defaults.
 */
class WithdrawalsDeadLetterTopicPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(WithdrawalsDeadLetterTopicProperties.class);

    private static NewTopic topic(AssertableApplicationContext context) {
        var properties = context.getBean(WithdrawalsDeadLetterTopicProperties.class);
        return new WithdrawalCommandConsumerConfig(properties).withdrawalsDeadLetterTopic();
    }

    @Test
    void theShippedDefaultsCreateATopicASingleLocalBrokerCanHost() {
        // A default above 1 replica would make the local single-broker setup refuse the topic, and the first dead
        // letter would then have nowhere to go.
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(topic(context).numPartitions()).isEqualTo(3);
            assertThat(topic(context).replicationFactor()).isEqualTo((short) 1);
        });
    }

    @Test
    void theDocumentedKeysReachTheTopic() {
        // Guards a key that stops binding, which leaves the topic on the defaults whatever the environment sets.
        runner.withPropertyValues(
                "spring.kafka.topic.wallet-withdrawals-dlt.partitions=6",
                "spring.kafka.topic.wallet-withdrawals-dlt.replicas=3"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(topic(context).numPartitions()).isEqualTo(6);
            assertThat(topic(context).replicationFactor()).isEqualTo((short) 3);
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"partitions", "replicas"})
    void aCountBelowOneFailsStartup(String key) {
        // Guards a topic Kafka refuses to create, found only when the first command is dead-lettered.
        String property = "spring.kafka.topic.wallet-withdrawals-dlt." + key;

        runner.withPropertyValues(property + "=0")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining(property + " must be at least 1"));
    }
}
