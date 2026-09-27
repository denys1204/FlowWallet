package com.flowwallet.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real class and builds the real topic bean, so a renamed key or a config that never reaches the
 * {@code NewTopic} shows up here rather than as a topic created with the broker's defaults.
 */
class PaymentEventsTopicPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PaymentEventsTopicProperties.class, KafkaConfig.class);

    @Test
    @DisplayName("the shipped defaults create a topic a single local broker can host")
    void defaultsFitOneBroker() {
        // A default above 1 replica or 1 in-sync replica would make the local single-broker setup refuse the topic or
        // every acks=all send.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NewTopic topic = context.getBean(NewTopic.class);

            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
            assertThat(topic.configs()).containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1");
        });
    }

    @Test
    @DisplayName("the documented keys reach the topic, min.insync.replicas included")
    void configuredValuesReachTheTopic() {
        // A key that stops binding leaves the topic on the broker default of 1 in-sync replica, where the leader
        // alone acknowledges a send.
        runner.withPropertyValues(
                "spring.kafka.topic.payment-events.partitions=6",
                "spring.kafka.topic.payment-events.replicas=3",
                "spring.kafka.topic.payment-events.min-insync-replicas=2"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            NewTopic topic = context.getBean(NewTopic.class);

            assertThat(topic.numPartitions()).isEqualTo(6);
            assertThat(topic.replicationFactor()).isEqualTo((short) 3);
            assertThat(topic.configs()).containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
        });
    }

    @Test
    @DisplayName("more in-sync replicas than replicas fails startup instead of refusing every send")
    void minInsyncReplicasAboveReplicasFailsStartup() {
        // Kafka accepts such a topic, and then every acks=all send fails with NotEnoughReplicas.
        runner.withPropertyValues(
                "spring.kafka.topic.payment-events.replicas=1",
                "spring.kafka.topic.payment-events.min-insync-replicas=2"
        ).run(context -> assertThat(context).hasFailed());
    }
}
