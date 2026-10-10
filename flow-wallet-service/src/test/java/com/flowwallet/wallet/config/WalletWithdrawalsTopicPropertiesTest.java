package com.flowwallet.wallet.config;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.outbox.OutboxTopic;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real class and builds the real topic bean, so a renamed key or a value that never reaches the
 * {@code NewTopic} shows up here rather than as a topic created with the broker's defaults.
 */
class WalletWithdrawalsTopicPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(WalletWithdrawalsTopicProperties.class, KafkaProducerConfig.class);

    @Test
    void theClassDefaultsCreateATopicASingleLocalBrokerCanHost() {
        // A default above 1 replica or 1 in-sync replica would make the local single-broker setup refuse the topic or
        // every acks=all send of a debited withdrawal's command.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            NewTopic topic = context.getBean(NewTopic.class);

            assertThat(topic.name()).isEqualTo(KafkaConstants.WALLET_WITHDRAWALS_TOPIC);
            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
            assertThat(topic.configs()).containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1");
        });
    }

    @Test
    void theShippedYamlKeepsTheClassDefaults() throws Exception {
        // Guards the YAML fallbacks drifting from the class defaults: the YAML value is what a run without .env
        // gets, so a 3 there would refuse the topic on a single local broker.
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml);
        PropertySourcesPropertyResolver shipped = new PropertySourcesPropertyResolver(sources);
        WalletWithdrawalsTopicProperties defaults = new WalletWithdrawalsTopicProperties();

        assertThat(shipped.getProperty("spring.kafka.topic.wallet-withdrawals.partitions", Integer.class))
                .isEqualTo(defaults.getPartitions());
        assertThat(shipped.getProperty("spring.kafka.topic.wallet-withdrawals.replicas", Integer.class))
                .isEqualTo(defaults.getReplicas());
        assertThat(shipped.getProperty("spring.kafka.topic.wallet-withdrawals.min-insync-replicas", Integer.class))
                .isEqualTo(defaults.getMinInsyncReplicas());
    }

    @Test
    void theDocumentedKeysReachTheTopic() {
        // A key that stops binding leaves the topic on the broker default of 1 in-sync replica, where the leader
        // alone acknowledges a send.
        runner.withPropertyValues(
                "spring.kafka.topic.wallet-withdrawals.partitions=6",
                "spring.kafka.topic.wallet-withdrawals.replicas=3",
                "spring.kafka.topic.wallet-withdrawals.min-insync-replicas=2"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            NewTopic topic = context.getBean(NewTopic.class);

            assertThat(topic.numPartitions()).isEqualTo(6);
            assertThat(topic.replicationFactor()).isEqualTo((short) 3);
            assertThat(topic.configs()).containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
        });
    }

    @Test
    void moreInSyncReplicasThanReplicasFailsStartup() {
        // Kafka accepts such a topic, and then every acks=all send fails with NotEnoughReplicas.
        runner.withPropertyValues(
                "spring.kafka.topic.wallet-withdrawals.replicas=1",
                "spring.kafka.topic.wallet-withdrawals.min-insync-replicas=2"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theOutboxSendsWithdrawalCommandsToTheirTopic() {
        // Guards the topic the shared outbox sends to: a command anywhere else never reaches Payment Service, and
        // one on payment.events would reach the wallet's own consumer.
        runner.run(context -> assertThat(context.getBean(OutboxTopic.class).name())
                .isEqualTo(KafkaConstants.WALLET_WITHDRAWALS_TOPIC));
    }
}
