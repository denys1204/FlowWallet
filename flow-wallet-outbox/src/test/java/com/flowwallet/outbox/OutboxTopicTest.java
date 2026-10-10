package com.flowwallet.outbox;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTopicTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void aTopicWithoutANameIsRefused(String name) {
        // Guards a service starting with a topic the producer refuses only at the first send, which would leave every
        // row to fail its retries.
        assertThatThrownBy(() -> new OutboxTopic(name)).isInstanceOf(IllegalArgumentException.class);
    }
}
