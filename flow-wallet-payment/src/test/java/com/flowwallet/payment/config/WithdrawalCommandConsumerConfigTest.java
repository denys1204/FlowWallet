package com.flowwallet.payment.config;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.payout.ConflictingWithdrawalCommandException;
import com.flowwallet.payment.payout.UnreadableWithdrawalCommandException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WithdrawalCommandConsumerConfigTest {
    private final WithdrawalCommandConsumerConfig config =
            new WithdrawalCommandConsumerConfig(new WithdrawalsDeadLetterTopicProperties());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>(KafkaConstants.WALLET_WITHDRAWALS_TOPIC, 1, 42L, "ref-1", "{}");

    @SuppressWarnings("unchecked")
    private DefaultErrorHandler errorHandlerWithRetries(int retries) {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        var retry = new WithdrawalCommandRetryProperties();
        retry.setMaxAttempts(retries);
        retry.setInitialIntervalMs(1);
        retry.setMaxIntervalMs(1);
        return config.withdrawalCommandErrorHandler(kafkaTemplate, retry, meterRegistry);
    }

    private double deadLetters() {
        return meterRegistry.counter(WithdrawalCommandRetryListener.DEAD_LETTER_COUNTER).count();
    }

    private static String shipped(String key) throws Exception {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml);
        return new PropertySourcesPropertyResolver(sources).getProperty(key);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"unreadable", "conflicting"})
    @SuppressWarnings("unchecked")
    void aCommandThatFailsTheSameWayEveryTimeIsDeadLetteredAtOnceAndCounted(String kind) {
        // Guards retrying a record that can never become a payout, which holds its partition for the whole backoff,
        // and a dead letter nobody hears of: it is a debited withdrawal waiting for an operator.
        DefaultErrorHandler errorHandler = errorHandlerWithRetries(3);
        RuntimeException failure = "unreadable".equals(kind)
                ? new UnreadableWithdrawalCommandException("no header")
                : new ConflictingWithdrawalCommandException("ref-1");

        boolean settled = errorHandler.handleOne(failure, record, consumer, container);

        assertThat(settled).isTrue();
        ArgumentCaptor<ProducerRecord<String, String>> published = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(published.capture());
        assertThat(published.getValue().topic()).isEqualTo(WithdrawalCommandConsumerConfig.DEAD_LETTER_TOPIC);
        assertThat(published.getValue().value()).isEqualTo("{}");
        assertThat(deadLetters()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anyOtherFailureIsDeadLetteredOnlyAfterTheConfiguredRetries() {
        // Guards dead-lettering a command a short payment_db outage held up, which an operator would then have to
        // republish, and one that keeps failing blocking its partition for good.
        DefaultErrorHandler errorHandler = errorHandlerWithRetries(2);
        RuntimeException outage = new IllegalStateException("payment_db unavailable");

        assertThat(errorHandler.handleOne(outage, record, consumer, container)).isFalse();
        assertThat(errorHandler.handleOne(outage, record, consumer, container)).isFalse();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

        assertThat(errorHandler.handleOne(outage, record, consumer, container)).isTrue();
        verify(kafkaTemplate).send(any(ProducerRecord.class));
        assertThat(deadLetters()).isEqualTo(1.0);
    }

    @Test
    void theDeadLetterTopicKeepsItsRecordsForGood() {
        // Guards the broker's seven-day default deleting a debited withdrawal that nobody replayed in time.
        NewTopic topic = config.withdrawalsDeadLetterTopic();

        assertThat(topic.name()).isEqualTo(WithdrawalCommandConsumerConfig.DEAD_LETTER_TOPIC);
        assertThat(topic.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "-1");
    }

    @Test
    void theShippedConsumerStartsFromTheOldestRecordAndNeverCreatesTheTopic() throws Exception {
        // Guards a new group skipping the commands sent before it joined, and a consumer that subscribes first having
        // the broker create wallet.withdrawals with its defaults instead of the wallet's declared partitions.
        assertThat(shipped("spring.kafka.consumer.auto-offset-reset")).isEqualTo("earliest");
        assertThat(shipped("spring.kafka.consumer.properties.allow.auto.create.topics")).isEqualTo("false");
        assertThat(shipped("spring.kafka.consumer.group-id")).isEqualTo("flow-wallet-payment");
        assertThat(shipped("spring.kafka.listener.ack-mode")).isEqualTo("RECORD");
        assertThat(shipped("spring.kafka.admin.modify-topic-configs")).isNull();
    }
}
