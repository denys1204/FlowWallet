package com.flowwallet.payment.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxEndpointTest {
    private OutboxOperations operations;
    private OutboxEndpoint endpoint;

    @BeforeEach
    void setUp() {
        operations = mock(OutboxOperations.class);
        endpoint = new OutboxEndpoint(operations);
    }

    @Test
    void failedExposesTheFailedCount() {
        when(operations.failedCount()).thenReturn(4L);

        assertThat(endpoint.failed()).containsEntry("failed", 4L);
    }

    @Test
    void requeueFailedExposesTheRequeuedCount() {
        when(operations.requeueFailed()).thenReturn(2);

        assertThat(endpoint.requeueFailed()).containsEntry("requeued", 2);
    }
}
