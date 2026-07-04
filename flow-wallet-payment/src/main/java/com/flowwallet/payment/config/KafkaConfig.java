package com.flowwallet.payment.config;

import com.flowwallet.common.constant.KafkaConstants;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    @Value("${spring.kafka.topic.payment-events.partitions:3}")
    private int paymentEventsPartitions;

    @Value("${spring.kafka.topic.payment-events.replicas:1}")
    private short paymentEventsReplicas;

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(KafkaConstants.PAYMENT_EVENTS_TOPIC)
                .partitions(paymentEventsPartitions)
                .replicas(paymentEventsReplicas)
                .build();
    }
}
