package com.flowwallet.wallet.config;

import com.flowwallet.wallet.balance.UnreadablePaymentEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens to a record the listener could not handle.
 * <p>
 * Without this the container's default recoverer logs the record and moves on, which in a service that
 * credits money means a payment quietly not arriving with nothing durable to show for it. Deferring it was
 * tempting and wrong: one poisonous record blocks its partition, and with the transaction reference as the
 * partition key that stalls a third of all credits, for people with no connection to the failure.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /**
     * The wallet's own dead-letter topic, deliberately not the payment service's. What lands here is a
     * failed {@code ConsumerRecord} with this consumer's serialization; the producer-side topic would carry
     * raw outbox rows under a different contract, and mixing the two makes both unreadable.
     */
    public static final String DEAD_LETTER_TOPIC = "payment.events.wallet.DLT";

    @Value("${spring.kafka.topic.payment-events-dlt.partitions:3}")
    private int deadLetterPartitions;

    @Value("${spring.kafka.topic.payment-events-dlt.replicas:1}")
    private short deadLetterReplicas;

    /**
     * Declared rather than left to broker auto-creation, which is off on any broker worth running and would
     * otherwise turn a dead-letter publish into a failure of its own — the record then goes nowhere and the
     * consumer retries it forever.
     */
    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC)
                .partitions(deadLetterPartitions)
                .replicas(deadLetterReplicas)
                .build();
    }

    @Bean
    public DefaultErrorHandler paymentEventErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            PaymentEventConsumerProperties retry
    ) {
        // Partition -1 lets the broker choose: the dead-letter topic need not have the same partition count
        // as the source, and pinning the original partition would fail whenever it has fewer.
        var recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, -1)
        );

        var backOff = new ExponentialBackOff();
        backOff.setMaxAttempts(retry.getMaxAttempts());
        backOff.setInitialInterval(retry.getInitialIntervalMs());
        backOff.setMultiplier(retry.getMultiplier());
        backOff.setMaxInterval(retry.getMaxIntervalMs());

        var errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // A record that cannot be parsed or typed will not parse better in five seconds. Retrying it only
        // delays the dead-letter that is already the answer.
        errorHandler.addNotRetryableExceptions(UnreadablePaymentEventException.class);

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Attempt {} failed for offset {} on {}: {}",
                        deliveryAttempt, record.offset(), record.topic(), exception.getMessage()));

        return errorHandler;
    }
}
