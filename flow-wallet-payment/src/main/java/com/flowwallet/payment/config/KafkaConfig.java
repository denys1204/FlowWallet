package com.flowwallet.payment.config;

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
