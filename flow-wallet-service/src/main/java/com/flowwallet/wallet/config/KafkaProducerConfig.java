package com.flowwallet.wallet.config;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.outbox.OutboxTopic;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic Wallet Service's outbox publishes to. The wallet declares {@code wallet.withdrawals} because it is the
 * producer. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
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
}
