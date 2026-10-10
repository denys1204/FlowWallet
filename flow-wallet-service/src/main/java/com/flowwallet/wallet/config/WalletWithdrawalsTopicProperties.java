package com.flowwallet.wallet.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * How {@code wallet.withdrawals} is created. Wallet Service owns the topic because it produces to it, and
 * {@code spring.kafka.admin.modify-topic-configs} carries a changed {@code min-insync-replicas} to a topic that
 * already exists.
 * See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "spring.kafka.topic.wallet-withdrawals")
public class WalletWithdrawalsTopicProperties {
    @Min(value = 1, message = "spring.kafka.topic.wallet-withdrawals.partitions must be at least 1")
    private int partitions = 3;

    @Min(value = 1, message = "spring.kafka.topic.wallet-withdrawals.replicas must be at least 1")
    private int replicas = 1;

    /**
     * Replicas that must hold a command before an {@code acks=all} send succeeds. With more than one replica and the
     * broker default of 1, the leader alone can acknowledge a debited withdrawal's command and lose it with its disk.
     */
    @Min(value = 1, message = "spring.kafka.topic.wallet-withdrawals.min-insync-replicas must be at least 1")
    private int minInsyncReplicas = 1;

    @AssertTrue(message = "spring.kafka.topic.wallet-withdrawals.min-insync-replicas must not exceed replicas")
    public boolean isMinInsyncReplicasWithinReplicas() {
        return minInsyncReplicas <= replicas;
    }
}
