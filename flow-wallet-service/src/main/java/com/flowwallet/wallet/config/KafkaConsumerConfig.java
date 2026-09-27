package com.flowwallet.wallet.config;

import com.flowwallet.wallet.balance.UnreadablePaymentEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens to a record the listener could not handle: bounded retries, then the wallet's dead-letter topic.
 * The container's default recoverer would log the record and drop the payment. This error handler is the
 * wallet's only retry mechanism. See docs/adr/0010-idempotent-payment-event-consumer.md.
 */
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
     * publish would have the record redelivered without end. Retention is unlimited, because nothing else keeps
     * a payment that reached this topic: under the broker's default of seven days, a dead letter nobody replayed
     * in time would be deleted. {@code spring.kafka.admin.modify-topic-configs} applies it to an existing topic.
     * See docs/adr/0020-wallet-dead-letters-kept-and-counted.md.
     */
    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC)
                .partitions(deadLetterPartitions)
                .replicas(deadLetterReplicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }

    @Bean
    public DefaultErrorHandler paymentEventErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            PaymentEventConsumerProperties retry,
            MeterRegistry meterRegistry
    ) {
        // Partition -1 leaves the choice to the producer's partitioner: the dead-letter topic need not have the
        // same partition count as the source. Pinning the original partition would add a partition lookup to
        // every publish, only to fall back to the producer's choice whenever the topic has fewer.
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

        errorHandler.setRetryListeners(new PaymentEventRetryListener(meterRegistry));

        return errorHandler;
    }
}
