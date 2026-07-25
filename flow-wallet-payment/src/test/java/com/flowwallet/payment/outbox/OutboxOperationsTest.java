package com.flowwallet.payment.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxOperationsTest {
    private OutboxEventRepository repository;
    private OutboxOperations operations;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        operations = new OutboxOperations(repository);
    }

    @Test
    void failedCountReportsTheFailedBacklog() {
        when(repository.countByStatus(OutboxStatus.FAILED)).thenReturn(3L);

        assertThat(operations.failedCount()).isEqualTo(3L);
    }

    @Test
    void requeueFailedReturnsFailedEventsToPending() {
        when(repository.requeueFailed(OutboxStatus.PENDING, OutboxStatus.FAILED)).thenReturn(2);

        assertThat(operations.requeueFailed()).isEqualTo(2);
        verify(repository).requeueFailed(OutboxStatus.PENDING, OutboxStatus.FAILED);
    }
}
