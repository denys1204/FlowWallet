package com.flowwallet.wallet.support;

import com.flowwallet.contract.constant.KafkaConstants;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic Payment Service owns and the wallet consumes. The test broker creates no topic on first use, so every
 * topic the wallet itself writes has to come from its own beans, and this is the only one the tests add.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PeerTopics {
    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(KafkaConstants.PAYMENT_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}
