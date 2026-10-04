package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.outbox.PaymentOutboxService;
import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
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
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String REASON = "Payment failed via webhook";

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
        // A late failure event for an intent that already settled must not undo the success.
        PaymentTransaction tx = transactionWith(TransactionStatus.SUCCESS);
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure(failure("pi_123", "evt_fail"), REASON);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString(), any());
    }

    @Test
    void handleFailureIsIdempotentForAlreadyFailedTransaction() {
        // A retried failure event must not save again or publish a second PaymentFailedEvent.
        PaymentTransaction tx = transactionWith(TransactionStatus.FAILED);
        when(repository.existsByProviderEventId("evt_fail_2")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure(failure("pi_123", "evt_fail_2"), REASON);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString(), any());
    }

    @Test
    void handleFailureMarksPendingTransactionAsFailedAndPublishes() {
        // The ordinary path: a genuine failure must actually move the transaction to FAILED and publish it.
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure(failure("pi_123", "evt_fail"), REASON);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentFailed(tx, REASON, OCCURRED_AT);
    }

    @Test
    void handleSuccessPromotesFailedTransactionToSuccess() {
        // A retry that later succeeds must still credit the wallet, so FAILED is not treated as terminal.
        PaymentTransaction tx = transactionWith(TransactionStatus.FAILED);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess(success("pi_123", "evt_ok"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentCompleted(tx, OCCURRED_AT);
    }

    @Test
    void handleSuccessIsIdempotentForAlreadySuccessfulTransaction() {
        // A duplicated delivery of the same success must not save again or credit the wallet twice.
        PaymentTransaction tx = transactionWith(TransactionStatus.SUCCESS);
        when(repository.existsByProviderEventId("evt_ok_2")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess(success("pi_123", "evt_ok_2"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentCompleted(any(), any());
    }

    @Test
    void skipsWhenProviderEventAlreadyProcessed() {
        // A replayed webhook event id must short-circuit before the transaction is even looked up.
        when(repository.existsByProviderEventId("evt_dup")).thenReturn(true);

        handler.handleSuccess(success("pi_123", "evt_dup"));

        verify(repository, never()).findByProviderTransactionId(anyString());
        verify(outboxService, never()).publishPaymentCompleted(any(), any());
    }

    @Test
    void handleSuccessSettlesPendingTransactionAndPublishes() {
        // The ordinary path: a genuine success must actually move the transaction to SUCCESS and publish it.
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.existsByProviderEventId("evt_ok")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleSuccess(success("pi_123", "evt_ok"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(repository).save(tx);
        verify(outboxService).publishPaymentCompleted(tx, OCCURRED_AT);
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
        verify(outboxService, never()).publishPaymentCompleted(any(), any());
    }

    @Test
    void aFailureForAnIntentThisServiceNeverCreatedIsAcknowledgedAndIgnored() {
        // Same case as the success above, for a failure event: the webhook must still be acknowledged.
        when(repository.existsByProviderEventId("evt_fail")).thenReturn(false);
        when(repository.findByProviderTransactionId("pi_missing")).thenReturn(Optional.empty());

        assertThatCode(() -> handler.handleFailure(failure("pi_missing", "evt_fail"), REASON))
                .doesNotThrowAnyException();
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentFailed(any(), anyString(), any());
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
                currency,
                OCCURRED_AT
        );

        assertThat(handler.handleSuccess(result)).isFalse();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getProviderEventId()).isNull();
        verify(repository, never()).save(any());
        verify(outboxService, never()).publishPaymentCompleted(any(), any());
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
                "usd",
                OCCURRED_AT
        ));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        verify(outboxService).publishPaymentCompleted(tx, OCCURRED_AT);
    }

    @ParameterizedTest(name = "{0} retries on an optimistic-lock conflict inside its own transaction")
    @ValueSource(strings = {"handleSuccess", "handleFailure"})
    void bothWebhookPathsRetryAnOptimisticLockConflict(String method) {
        // Guards the retry being dropped or narrowed. Two webhooks for one transaction collide on @Version, and
        // without the retry the loser answers 500, so Stripe redelivers an event that a second attempt would
        // have applied at once. @Transactional must sit on the same method, so each attempt gets a fresh
        // transaction and reads the winner's row.
        Method handler = Arrays.stream(PaymentTransactionHandler.class.getMethods())
                .filter(candidate -> candidate.getName().equals(method))
                .findFirst()
                .orElseThrow();

        Retryable retryable = AnnotatedElementUtils.findMergedAnnotation(handler, Retryable.class);
        assertThat(retryable).isNotNull();
        assertThat(retryable.retryFor()).containsExactly(ObjectOptimisticLockingFailureException.class);
        assertThat(retryable.maxAttemptsExpression()).isEqualTo("${payment.retry.optimistic-lock.max-attempts:3}");
        assertThat(AnnotatedElementUtils.findMergedAnnotation(handler, Transactional.class)).isNotNull();
    }

    @Test
    void eachHandlerReportsWhetherItChangedThePayment() {
        // Guards the reconciler's count of payments it completed, which trusts these answers: true only when the
        // row moved and its event was written, false for a repeat, an event already seen or an unknown intent.
        PaymentTransaction paid = transactionWith(TransactionStatus.PENDING);
        PaymentTransaction declined = transactionWith(TransactionStatus.PENDING);
        PaymentTransaction untouched = transactionWith(TransactionStatus.PENDING);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(paid));
        when(repository.findByProviderTransactionId("pi_456")).thenReturn(Optional.of(declined));
        when(repository.findByProviderTransactionId("pi_789")).thenReturn(Optional.of(untouched));
        when(repository.findByProviderTransactionId("pi_missing")).thenReturn(Optional.empty());
        when(repository.existsByProviderEventId("evt_seen")).thenReturn(true);

        assertThat(handler.handleSuccess(success("pi_123", "evt_ok"))).isTrue();
        assertThat(handler.handleSuccess(success("pi_123", "evt_ok_again"))).isFalse();
        assertThat(handler.handleFailure(failure("pi_456", "evt_fail"), REASON)).isTrue();
        assertThat(handler.handleFailure(failure("pi_456", "evt_fail_again"), REASON)).isFalse();
        assertThat(handler.handleSuccess(success("pi_missing", "evt_other"))).isFalse();
        // A PENDING row, so only the event-id check can answer false here.
        assertThat(handler.handleSuccess(success("pi_789", "evt_seen"))).isFalse();
        assertThat(untouched.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void theFailureReasonIsTheCallersOwn() {
        // Guards every failure being published as a webhook failure: the reconciler reports a canceled intent,
        // and the event must say so.
        PaymentTransaction tx = transactionWith(TransactionStatus.PENDING);
        when(repository.findByProviderTransactionId("pi_123")).thenReturn(Optional.of(tx));

        handler.handleFailure(failure("pi_123", "reconcile:pi_123"), "Payment canceled at the provider");

        verify(outboxService).publishPaymentFailed(tx, "Payment canceled at the provider", OCCURRED_AT);
    }

    private static WebhookResult success(String providerTransactionId, String providerEventId) {
        return new WebhookResult(
                providerTransactionId,
                providerEventId,
                WebhookEventType.PAYMENT_SUCCESS,
                new BigDecimal("50.00"),
                "USD",
                OCCURRED_AT
        );
    }

    private static WebhookResult failure(String providerTransactionId, String providerEventId) {
        return new WebhookResult(
                providerTransactionId,
                providerEventId,
                WebhookEventType.PAYMENT_FAILURE,
                new BigDecimal("50.00"),
                "USD",
                OCCURRED_AT
        );
    }

    private PaymentTransaction transactionWith(TransactionStatus status) {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                "ref-1",
                new BigDecimal("50.00"),
                "USD",
                "STRIPE"
        );

        PaymentTransaction tx = PaymentTransaction.create(request, "user-1", PaymentProvider.STRIPE);

        switch (status) {
            case SUCCESS -> tx.markAsSuccess("evt_previous");
            case FAILED -> tx.markAsFailed("evt_previous");
            case PENDING -> { /* create() already yields PENDING */ }
        }
        return tx;
    }
}
