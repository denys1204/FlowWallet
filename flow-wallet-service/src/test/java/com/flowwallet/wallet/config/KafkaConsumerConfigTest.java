package com.flowwallet.wallet.config;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.balance.UnreadablePaymentEventException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaConsumerConfigTest {
    private final KafkaConsumerConfig config = new KafkaConsumerConfig();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>(KafkaConstants.PAYMENT_EVENTS_TOPIC, 1, 42L, "ref-1", "{}");

    @SuppressWarnings("unchecked")
    private DefaultErrorHandler errorHandlerWithRetries(int retries) {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        var retry = new PaymentEventConsumerProperties();
        retry.setMaxAttempts(retries);
        retry.setInitialIntervalMs(1);
        retry.setMaxIntervalMs(1);
        return config.paymentEventErrorHandler(kafkaTemplate, retry, meterRegistry);
    }

    private double deadLetters() {
        return meterRegistry.counter(PaymentEventRetryListener.DEAD_LETTER_COUNTER).count();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreadableRecordGoesToTheDeadLetterTopicWithoutARetry() {
        // Every attempt at an unreadable record fails the same way. Retrying it would only hold its partition
        // for the whole backoff before the same dead-letter publish.
        DefaultErrorHandler errorHandler = errorHandlerWithRetries(3);

        boolean settled = errorHandler.handleOne(
                UnreadablePaymentEventException.missingEventType(),
                record,
                consumer,
                container
        );

        assertThat(settled).isTrue();
        ArgumentCaptor<ProducerRecord<String, String>> published = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(published.capture());
        assertThat(published.getValue().topic()).isEqualTo(KafkaConsumerConfig.DEAD_LETTER_TOPIC);
        assertThat(published.getValue().value()).isEqualTo("{}");
        assertThat(deadLetters()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anyOtherFailureIsDeadLetteredOnlyAfterTheConfiguredRetries() {
        // Guards both directions: a failure a later attempt can fix, such as a database outage, must get its
        // retries, and a record that keeps failing must still reach the dead-letter topic rather than block its
        // partition. max-attempts counts redeliveries after the first attempt.
        DefaultErrorHandler errorHandler = errorHandlerWithRetries(2);
        RuntimeException outage = new IllegalStateException("database unavailable");

        assertThat(errorHandler.handleOne(outage, record, consumer, container)).isFalse();
        assertThat(errorHandler.handleOne(outage, record, consumer, container)).isFalse();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(deadLetters()).isZero();

        assertThat(errorHandler.handleOne(outage, record, consumer, container)).isTrue();
        verify(kafkaTemplate).send(any(ProducerRecord.class));
        assertThat(deadLetters()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedDeadLetterPublishIsNotCounted() {
        // The counter tells an operator a payment waits on the dead-letter topic. A publish that failed left the
        // record on payment.events for redelivery, so counting it would report a dead letter that does not exist.
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        var retry = new PaymentEventConsumerProperties();
        retry.setMaxAttempts(0);
        DefaultErrorHandler errorHandler = config.paymentEventErrorHandler(kafkaTemplate, retry, meterRegistry);

        errorHandler.handleOne(new IllegalStateException("database unavailable"), record, consumer, container);

        assertThat(deadLetters()).isZero();
    }

    @Test
    void theDeadLetterTopicKeepsItsRecordsWithoutATimeLimit() {
        // Nothing else holds a payment that reached this topic. Under the broker's default retention of seven
        // days, a dead letter nobody replayed within the week would be deleted with no trace in the wallet.
        ReflectionTestUtils.setField(config, "deadLetterPartitions", 3);
        ReflectionTestUtils.setField(config, "deadLetterReplicas", (short) 1);

        NewTopic topic = config.paymentEventsDeadLetterTopic();

        assertThat(topic.name()).isEqualTo(KafkaConsumerConfig.DEAD_LETTER_TOPIC);
        assertThat(topic.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "-1");
    }

    private static String shipped(String key) throws Exception {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        return String.valueOf(yaml.getProperty(key));
    }

    @Test
    void theShippedConfigurationCreatesTopicsOrFailsStartupAndUpdatesExistingOnes() throws Exception {
        // Without fail-fast, a start while the broker is down logs one error and never creates the dead-letter
        // topic. Without modify-topic-configs, a topic that already exists keeps the broker's retention.
        assertThat(shipped("spring.kafka.admin.fail-fast")).isEqualTo("true");
        assertThat(shipped("spring.kafka.admin.modify-topic-configs")).isEqualTo("true");
    }

    @Test
    void aGroupWithoutCommittedOffsetsStartsFromTheOldestRecord() throws Exception {
        // With latest, a new group id or one whose offsets expired would skip every payment published before it
        // joined, and those payments would never be credited. The value is literal so no variable can change it.
        assertThat(shipped("spring.kafka.consumer.auto-offset-reset")).isEqualTo("earliest");
    }
}
