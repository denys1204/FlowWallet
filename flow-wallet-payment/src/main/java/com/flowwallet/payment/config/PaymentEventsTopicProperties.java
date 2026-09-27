package com.flowwallet.payment.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * How {@code payment.events} is created. Kafka applies these values only when it creates the topic; an existing
 * topic keeps its own unless {@code spring.kafka.admin.modify-topic-configs} is on.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "spring.kafka.topic.payment-events")
public class PaymentEventsTopicProperties {
    @Min(value = 1, message = "spring.kafka.topic.payment-events.partitions must be at least 1")
    private int partitions = 3;

    @Min(value = 1, message = "spring.kafka.topic.payment-events.replicas must be at least 1")
    private int replicas = 1;

    /**
     * Replicas that must hold a record before an {@code acks=all} send succeeds. With more than one replica and the
     * broker default of 1, the leader alone can acknowledge a record and lose it with its disk.
     */
    @Min(value = 1, message = "spring.kafka.topic.payment-events.min-insync-replicas must be at least 1")
    private int minInsyncReplicas = 1;

    @AssertTrue(message = "spring.kafka.topic.payment-events.min-insync-replicas must not exceed replicas")
    public boolean isMinInsyncReplicasWithinReplicas() {
        return minInsyncReplicas <= replicas;
    }
}
