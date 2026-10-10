package com.flowwallet.payment.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * How {@code wallet.withdrawals.payment.DLT} is created. Payment Service creates the topic itself, so the values
 * apply from the first start. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "spring.kafka.topic.wallet-withdrawals-dlt")
public class WithdrawalsDeadLetterTopicProperties {
    @Min(value = 1, message = "spring.kafka.topic.wallet-withdrawals-dlt.partitions must be at least 1")
    private int partitions = 3;

    @Min(value = 1, message = "spring.kafka.topic.wallet-withdrawals-dlt.replicas must be at least 1")
    private int replicas = 1;
}
