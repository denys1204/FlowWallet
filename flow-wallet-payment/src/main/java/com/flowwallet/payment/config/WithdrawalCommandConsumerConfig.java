package com.flowwallet.payment.config;

import com.flowwallet.payment.payout.ConflictingWithdrawalCommandException;
import com.flowwallet.payment.payout.UnreadableWithdrawalCommandException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens to a withdrawal command the listener could not accept: bounded retries, then Payment Service's own
 * dead-letter topic. A command that reaches it is a debited withdrawal waiting for an operator, so the topic keeps
 * its records without a time limit and every dead letter is logged and counted.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Configuration
@RequiredArgsConstructor
public class WithdrawalCommandConsumerConfig {
    /**
     * The consumer's own dead-letter topic, apart from the wallet's {@code payment.events.wallet.DLT}.
     */
    public static final String DEAD_LETTER_TOPIC = "wallet.withdrawals.payment.DLT";

    private final WithdrawalsDeadLetterTopicProperties deadLetterTopic;

    /**
     * Declared with unlimited retention, because nothing else keeps a debited withdrawal that reached it. Payment
     * Service creates the topic itself, so it needs no {@code spring.kafka.admin.modify-topic-configs}.
     */
    @Bean
    public NewTopic withdrawalsDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC)
                .partitions(deadLetterTopic.getPartitions())
                .replicas(deadLetterTopic.getReplicas())
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }

    @Bean
    public DefaultErrorHandler withdrawalCommandErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            WithdrawalCommandRetryProperties retry,
            MeterRegistry meterRegistry
    ) {
        // Partition -1 leaves the choice to the producer's partitioner, as for the wallet's dead letters.
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

        // A record that cannot become a payout, or a command that conflicts with an accepted one, fails the same
        // way on every attempt, so it is dead-lettered at once.
        errorHandler.addNotRetryableExceptions(
                UnreadableWithdrawalCommandException.class,
                ConflictingWithdrawalCommandException.class
        );

        errorHandler.setRetryListeners(new WithdrawalCommandRetryListener(meterRegistry));

        return errorHandler;
    }
}
