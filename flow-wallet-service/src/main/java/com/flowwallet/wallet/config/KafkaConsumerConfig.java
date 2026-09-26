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
 * What happens to a record the listener could not handle: bounded retries, then the wallet's dead-letter topic.
 * The container's default recoverer would log the record and drop the payment. This error handler is the
 * wallet's only retry mechanism. See docs/adr/0010-idempotent-payment-event-consumer.md.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /**
     * The wallet's own dead-letter topic for failed consumer records, kept apart from Payment Service's
     * dead-letter store of outbox rows.
     */
    public static final String DEAD_LETTER_TOPIC = "payment.events.wallet.DLT";

    @Value("${spring.kafka.topic.payment-events-dlt.partitions:3}")
    private int deadLetterPartitions;

    @Value("${spring.kafka.topic.payment-events-dlt.replicas:1}")
    private short deadLetterReplicas;

    /**
     * Declared rather than left to broker auto-creation, which production brokers disable: a failed dead-letter
     * publish would have the record redelivered without end.
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

        // An unreadable record fails the same way on every attempt, so it is dead-lettered at once.
        errorHandler.addNotRetryableExceptions(UnreadablePaymentEventException.class);

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Attempt {} failed for offset {} on {}: {}",
                        deliveryAttempt, record.offset(), record.topic(), exception.getMessage()));

        return errorHandler;
    }
}
