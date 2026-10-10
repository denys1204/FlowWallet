package com.flowwallet.outbox;

/**
 * The topic a service's outbox publishes to. Each service declares one bean of it with the contract's constant, and
 * startup fails without one. It is a bean and not a property because the environment can override any property, and
 * topic names are compile-time constants. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 *
 * @param name the Kafka topic, a constant from {@code KafkaConstants}
 */
public record OutboxTopic(String name) {
    public OutboxTopic {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An outbox topic needs a name");
        }
    }
}
