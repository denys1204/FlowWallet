package com.flowwallet.payment.config;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import com.flowwallet.payment.payout.UnreadableWithdrawalCommandException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
class WithdrawalCommandRetryListenerTest {
    private static final String USER = "c7e4a1b2-5d3f-4e6a-8b9c-0d1e2f3a4b5c";

    @Test
    void theRetryAndDeadLetterLinesOfAnUnparseableCommandQuoteNothingFromIt(CapturedOutput output) {
        // Guards a user id in a log line (ADR 0027): these lines print the most specific cause, so the exception for a
        // command Jackson rejects must carry nothing of Jackson's message, which quotes the command.
        String json = "{\"amount\":\"" + USER + "\"}";
        JacksonException parseFailure = catchThrowableOfType(
                JacksonException.class,
                () -> new ObjectMapper().readValue(json, WithdrawalRequestedEvent.class)
        );
        var failure = new ListenerExecutionFailedException(
                "Listener failed",
                UnreadableWithdrawalCommandException.unparseable(parseFailure)
        );
        var record = new ConsumerRecord<>(KafkaConstants.WALLET_WITHDRAWALS_TOPIC, 0, 7L, "ref-1", json);
        var retryListener = new WithdrawalCommandRetryListener(new SimpleMeterRegistry());

        retryListener.failedDelivery(record, failure, 1);
        retryListener.recovered(record, failure);

        assertThat(parseFailure.getMessage()).contains(USER);
        assertThat(output).contains("Attempt 1 failed", "Dead-lettered").doesNotContain(USER);
    }
}
