package com.flowwallet.wallet.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.RetryListener;

/**
 * Reports each failed attempt and each record the error handler dead-letters. Nothing consumes the dead-letter
 * topic, so the ERROR line and the {@value #DEAD_LETTER_COUNTER} counter are how an operator learns that a payment
 * waits there. The listener gets the container's wrapper exception, so the lines name its most specific cause.
 * See docs/adr/0020-wallet-dead-letters-kept-and-counted.md.
 */
@Slf4j
class PaymentEventRetryListener implements RetryListener {
    static final String DEAD_LETTER_COUNTER = "wallet.consumer.dead.letters";

    private final Counter deadLetters;

    PaymentEventRetryListener(MeterRegistry meterRegistry) {
        this.deadLetters = Counter.builder(DEAD_LETTER_COUNTER)
                .description("Payment event records published to " + KafkaConsumerConfig.DEAD_LETTER_TOPIC)
                .register(meterRegistry);
    }

    @Override
    public void failedDelivery(@NonNull ConsumerRecord<?, ?> record, @Nullable Exception ex, int deliveryAttempt) {
        log.warn(
                "Attempt {} failed for {}-{} at offset {}: {}",
                deliveryAttempt,
                record.topic(),
                record.partition(),
                record.offset(),
                mostSpecificCause(ex)
        );
    }

    @Override
    public void recovered(@NonNull ConsumerRecord<?, ?> record, @Nullable Exception ex) {
        deadLetters.increment();
        log.error(
                "Dead-lettered {}-{} at offset {} to {}: {}",
                record.topic(),
                record.partition(),
                record.offset(),
                KafkaConsumerConfig.DEAD_LETTER_TOPIC,
                mostSpecificCause(ex)
        );
    }

    @Override
    public void recoveryFailed(
            @NonNull ConsumerRecord<?, ?> record,
            @Nullable Exception original,
            @NonNull Exception failure
    ) {
        log.error(
                "Could not dead-letter {}-{} at offset {}, so it is redelivered: {}",
                record.topic(),
                record.partition(),
                record.offset(),
                mostSpecificCause(failure)
        );
    }

    private static String mostSpecificCause(@Nullable Exception ex) {
        return ex == null ? "no exception" : NestedExceptionUtils.getMostSpecificCause(ex).toString();
    }
}
