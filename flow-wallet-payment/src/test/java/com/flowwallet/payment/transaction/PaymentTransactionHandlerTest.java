package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.outbox.PaymentOutboxService;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

        handler.handleFailure(failure("pi_123", "evt_fail"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString());
    }

    @Test
    void handleFailureIsIdempotentForAlreadyFailedTransaction() {
        PaymentTransaction tx = transactionWith(TransactionStatus.FAILED);
        when(repository.existsByProviderEventId("evt_fail_2")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure(failure("pi_123", "evt_fail_2"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString());
    }

    @Test
    void handleFailureMarksPendingTransactionAsFailedAndPublishes() {
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure(failure("pi_123", "evt_fail"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentFailed(tx, "Payment failed via webhook");
    }

    @Test
    void handleSuccessPromotesFailedTransactionToSuccess() {
        PaymentTransaction tx = transactionWith(TransactionStatus.FAILED);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess(success("pi_123", "evt_ok"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentCompleted(tx);
    }

    @Test
    void handleSuccessIsIdempotentForAlreadySuccessfulTransaction() {
        PaymentTransaction tx = transactionWith(TransactionStatus.SUCCESS);
        when(repository.existsByProviderEventId("evt_ok_2")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess(success("pi_123", "evt_ok_2"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentCompleted(any());
    }

    @Test
    void skipsWhenProviderEventAlreadyProcessed() {
        when(repository.existsByProviderEventId("evt_dup")).thenReturn(true);

        handler.handleSuccess(success("pi_123", "evt_dup"));

        verify(repository, never()).findByProviderTransactionId(anyString());
        verify(outboxService, never()).publishPaymentCompleted(any());
    }

    @Test
    void handleSuccessSettlesPendingTransactionAndPublishes() {
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess(success("pi_123", "evt_ok"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentCompleted(tx);
    }

    @Test
    void aSuccessForAnIntentThisServiceNeverCreatedIsAcknowledgedAndIgnored() {
        // A webhook's status reports delivery. An intent from `stripe trigger` or the dashboard is received
        // and verified, and a retry could not change anything, so it must not fail -- a 404 would make Stripe
        // retry and would read to it as "this endpoint does not exist".
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_missing")).thenReturn(Optional.empty());

        assertThatCode(() -> handler.handleSuccess(success("pi_missing", "evt_ok"))).doesNotThrowAnyException();
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentCompleted(any());
    }

    @Test
    void aFailureForAnIntentThisServiceNeverCreatedIsAcknowledgedAndIgnored() {
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_missing")).thenReturn(Optional.empty());

        assertThatCode(() -> handler.handleFailure(failure("pi_missing", "evt_fail"))).doesNotThrowAnyException();
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString());
    }

    @ParameterizedTest(name = "a success reporting {0} {1} leaves a 50.00 USD transaction unchanged")
    @CsvSource(
            value = {
                    "49.99, USD",
                    "5000, USD",
                    "50.00, EUR",
                    "NULL, USD",
                    "50.00, NULL",
            },
            nullValues = "NULL"
    )
    void aSuccessWhoseAmountOrCurrencyDiffersFromTheTransactionChangesNothing(String amount, String currency) {
        // The last check before a wallet is credited: a signed event for this intent that reports other terms
        // (a minor-unit amount taken as major, another currency, an event without either) must not settle the
        // payment. The webhook is still acknowledged, so nothing throws.
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));
        WebhookResult result = new WebhookResult(
                "pi_123",
                "evt_ok",
                WebhookEventType.PAYMENT_SUCCESS,
                amount == null ? null : new BigDecimal(amount),
                currency
        );

        assertThatCode(() -> handler.handleSuccess(result)).doesNotThrowAnyException();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getProviderEventId()).isNull();
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentCompleted(any());
    }

    @Test
    void aSuccessMatchesTheTransactionIgnoringTrailingZerosAndCurrencyCase() {
        // Stripe reports lower-case currencies and the converted amount carries its own scale; neither is a
        // difference, or every genuine payment would be refused.
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess(new WebhookResult(
                "pi_123",
                "evt_ok",
                WebhookEventType.PAYMENT_SUCCESS,
                new BigDecimal("50"),
                "usd"
        ));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(outboxService).publishPaymentCompleted(tx);
    }

    private static WebhookResult success(String providerTransactionId, String providerEventId) {
        return new WebhookResult(
                providerTransactionId,
                providerEventId,
                WebhookEventType.PAYMENT_SUCCESS,
                new BigDecimal("50.00"),
                "USD"
        );
    }

    private static WebhookResult failure(String providerTransactionId, String providerEventId) {
        return new WebhookResult(
                providerTransactionId,
                providerEventId,
                WebhookEventType.PAYMENT_FAILURE,
                new BigDecimal("50.00"),
                "USD"
        );
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
