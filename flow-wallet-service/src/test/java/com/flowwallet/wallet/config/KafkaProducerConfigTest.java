package com.flowwallet.wallet.config;

import com.flowwallet.contract.constant.KafkaConstants;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.support.ProducerListener;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class KafkaProducerConfigTest {
    private static final String USER = "c7e4a1b2-5d3f-4e6a-8b9c-0d1e2f3a4b5c";

    @Test
    void aFailedSendIsLoggedWithoutTheRecordsValue(CapturedOutput output) {
        // Guards a user id in a log line (ADR 0044): Spring Boot's own producer listener logs the first 100 characters
        // of the value, and a dead letter can have any shape, with the user id among them.
        ProducerListener<Object, Object> listener =
                new KafkaProducerConfig(new WalletWithdrawalsTopicProperties()).producerListenerWithoutContents();

        listener.onError(
                new ProducerRecord<>(KafkaConstants.WALLET_WITHDRAWALS_TOPIC, "ref-1", "{\"userId\":\"" + USER + "\"}"),
                null,
                new TimeoutException("broker down")
        );

        assertThat(output).contains(KafkaConstants.WALLET_WITHDRAWALS_TOPIC).doesNotContain(USER);
    }
}
