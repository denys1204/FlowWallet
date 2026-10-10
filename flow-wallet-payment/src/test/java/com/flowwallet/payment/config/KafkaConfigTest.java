package com.flowwallet.payment.config;

import com.flowwallet.contract.constant.KafkaConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {
    @Test
    void theOutboxPublishesPaymentEventsToTheirTopic() {
        // Guards the topic the shared outbox sends to: payment events anywhere else never reach the wallet's consumer.
        KafkaConfig config = new KafkaConfig(new PaymentEventsTopicProperties());

        assertThat(config.outboxTopic().name()).isEqualTo(KafkaConstants.PAYMENT_EVENTS_TOPIC);
    }
}
