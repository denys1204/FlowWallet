package com.flowwallet.payment.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.RetryListener;

/**
 * Reports each failed attempt and each command the error handler dead-letters. Nothing consumes the dead-letter
 * topic, so the ERROR line and the {@value #DEAD_LETTER_COUNTER} counter are how an operator learns that a debited
 * withdrawal waits there. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Slf4j
class WithdrawalCommandRetryListener implements RetryListener {
    static final String DEAD_LETTER_COUNTER = "payment.consumer.dead.letters";

    private final Counter deadLetters;

    WithdrawalCommandRetryListener(MeterRegistry meterRegistry) {
        this.deadLetters = Counter.builder(DEAD_LETTER_COUNTER)
                .description("Withdrawal commands published to " + WithdrawalCommandConsumerConfig.DEAD_LETTER_TOPIC)
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
                WithdrawalCommandConsumerConfig.DEAD_LETTER_TOPIC,
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
