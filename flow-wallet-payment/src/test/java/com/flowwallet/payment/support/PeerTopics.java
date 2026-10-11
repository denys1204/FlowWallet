package com.flowwallet.payment.support;

import com.flowwallet.contract.constant.KafkaConstants;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic Wallet Service owns and Payment Service consumes. The test broker creates no topic on first use, and
 * Payment Service's consumer does not ask it to, so every topic it writes has to come from its own beans, and this is
 * the only one the tests add.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PeerTopics {
    @Bean
    NewTopic walletWithdrawalsTopic() {
        return TopicBuilder.name(KafkaConstants.WALLET_WITHDRAWALS_TOPIC).partitions(3).replicas(1).build();
    }
}
