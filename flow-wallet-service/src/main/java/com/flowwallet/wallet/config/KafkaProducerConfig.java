package com.flowwallet.wallet.config;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.outbox.OutboxTopic;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;

/**
 * The topic Wallet Service's outbox publishes to, and how a send that fails is logged. The wallet declares {@code
 * wallet.withdrawals} because it is the producer.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Configuration
@RequiredArgsConstructor
public class KafkaProducerConfig {
    private final WalletWithdrawalsTopicProperties walletWithdrawalsTopic;

    @Bean
    public NewTopic walletWithdrawalsTopic() {
        return TopicBuilder.name(KafkaConstants.WALLET_WITHDRAWALS_TOPIC)
                .partitions(walletWithdrawalsTopic.getPartitions())
                .replicas(walletWithdrawalsTopic.getReplicas())
                .config(
                        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                        String.valueOf(walletWithdrawalsTopic.getMinInsyncReplicas())
                )
                .build();
    }

    @Bean
    public OutboxTopic outboxTopic() {
        return new OutboxTopic(KafkaConstants.WALLET_WITHDRAWALS_TOPIC);
    }

    /**
     * Replaces Spring Boot's producer listener, which logs the key and the first 100 characters of the value of a
     * record that fails to send. The services' own records name the user after that point, but a dead letter can have
     * any shape, so a failed send logs the topic and the exception only.
     * See docs/adr/0044-unreadable-kafka-records-are-reported-without-their-content.md.
     */
    @Bean
    public ProducerListener<Object, Object> producerListenerWithoutContents() {
        LoggingProducerListener<Object, Object> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        return listener;
    }
}
