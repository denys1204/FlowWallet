package com.flowwallet.payment.config;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.outbox.OutboxTopic;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@RequiredArgsConstructor
public class KafkaConfig {
    private final PaymentEventsTopicProperties paymentEventsTopic;

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(KafkaConstants.PAYMENT_EVENTS_TOPIC)
                .partitions(paymentEventsTopic.getPartitions())
                .replicas(paymentEventsTopic.getReplicas())
                .config(
                        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                        String.valueOf(paymentEventsTopic.getMinInsyncReplicas())
                )
                .build();
    }

    /**
     * The topic Payment Service's outbox publishes to.
     * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
     */
    @Bean
    public OutboxTopic outboxTopic() {
        return new OutboxTopic(KafkaConstants.PAYMENT_EVENTS_TOPIC);
    }
}
