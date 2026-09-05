package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.outbox.PaymentOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the transaction state machine in {@link PaymentTransactionHandler}: SUCCESS is
 * terminal, a late failure must not overwrite it, and a retry may still promote FAILED -> SUCCESS.
 */
class PaymentTransactionHandlerTest {
    private PaymentTransactionRepository repository;
    private PaymentOutboxService outboxService;
    private PaymentTransactionHandler handler;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentTransactionRepository.class);
        outboxService = mock(PaymentOutboxService.class);
        handler = new PaymentTransactionHandler(repository, outboxService);
    }

    @Test
    void handleFailureIgnoresAlreadySuccessfulTransaction() {
        PaymentTransaction tx = transactionWith(TransactionStatus.SUCCESS);
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure("pi_123", "evt_fail");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString());
    }

    @Test
    void handleFailureIsIdempotentForAlreadyFailedTransaction() {
        PaymentTransaction tx = transactionWith(TransactionStatus.FAILED);
        when(repository.existsByProviderEventId("evt_fail_2")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure("pi_123", "evt_fail_2");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString());
    }

    @Test
    void handleFailureMarksPendingTransactionAsFailedAndPublishes() {
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure("pi_123", "evt_fail");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentFailed(tx, "Payment failed via webhook");
    }

    @Test
    void handleSuccessPromotesFailedTransactionToSuccess() {
        PaymentTransaction tx = transactionWith(TransactionStatus.FAILED);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess("pi_123", "evt_ok");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentCompleted(tx);
    }

    @Test
    void handleSuccessIsIdempotentForAlreadySuccessfulTransaction() {
        PaymentTransaction tx = transactionWith(TransactionStatus.SUCCESS);
        when(repository.existsByProviderEventId("evt_ok_2")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess("pi_123", "evt_ok_2");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentCompleted(any());
    }

    @Test
    void skipsWhenProviderEventAlreadyProcessed() {
        when(repository.existsByProviderEventId("evt_dup")).thenReturn(true);

        handler.handleSuccess("pi_123", "evt_dup");

        verify(repository, never()).findByProviderTransactionId(anyString());
        verify(outboxService, never()).publishPaymentCompleted(any());
    }

    @Test
    void handleSuccessSettlesPendingTransactionAndPublishes() {
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess("pi_123", "evt_ok");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentCompleted(tx);
    }

    @Test
    void handleSuccessThrowsWhenTransactionNotFound() {
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handleSuccess("pi_missing", "evt_ok"))
                .isInstanceOf(TransactionNotFoundException.class);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentCompleted(any());
    }

    @Test
    void handleFailureThrowsWhenTransactionNotFound() {
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handleFailure("pi_missing", "evt_fail"))
                .isInstanceOf(TransactionNotFoundException.class);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString());
    }

    private PaymentTransaction transactionWith(TransactionStatus status) {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                "ref-1",
                new BigDecimal("50.00"),
                "USD",
                "STRIPE"
        );

        PaymentTransaction tx = PaymentTransaction.create(request, "user-1");

        switch (status) {
            case SUCCESS -> tx.markAsSuccess("evt_previous");
            case FAILED -> tx.markAsFailed("evt_previous");
            case PENDING -> { /* create() already yields PENDING */ }
        }
        return tx;
    }
}
