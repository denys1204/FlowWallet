package com.flowwallet.payment.transaction;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.payment.config.PaymentReconciliationProperties;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import com.flowwallet.payment.provider.exception.PaymentLookupException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PendingPaymentReconcilerTest {
    private static final String USER_ID = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";

    private final PaymentTransactionRepository transactions = mock(PaymentTransactionRepository.class);
    private final PaymentProviderFactory providers = mock(PaymentProviderFactory.class);
    private final PaymentProviderStrategy strategy = mock(PaymentProviderStrategy.class);
    private final PaymentTransactionHandler handler = mock(PaymentTransactionHandler.class);
    private final PaymentReconciliationProperties properties = new PaymentReconciliationProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PendingPaymentReconciler reconciler =
            new PendingPaymentReconciler(transactions, providers, handler, properties, meters);
    private final Logger logger = (Logger) LoggerFactory.getLogger(PendingPaymentReconciler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(providers.getStrategy("STRIPE")).thenReturn(strategy);
        when(transactions.claimForReconciliation(anyLong(), any(), any())).thenReturn(1);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppenderAndClearInterrupt() {
        logger.detachAppender(logs);
        Thread.interrupted();
    }

    @Test
    void aPaymentStripeSettledIsCompletedThroughTheWebhookHandler() {
        // Guards the reconciler settling a payment by any route other than the webhook's handler, which holds the
        // amount and currency check and writes the outbox row in the same transaction.
        due(payment(1L, "pi_1"));
        WebhookResult succeeded = result("pi_1", WebhookEventType.PAYMENT_SUCCESS);
        when(strategy.checkPayment("pi_1")).thenReturn(succeeded);
        when(handler.handleSuccess(succeeded)).thenReturn(true);

        reconciler.reconcile();

        verify(handler).handleSuccess(succeeded);
        assertThat(outcomes("completed")).isEqualTo(1);
    }

    @Test
    void aPaymentStripeCanceledIsFailedWithTheCancelReason() {
        // Guards a canceled intent being published with the webhook's reason, or not at all.
        due(payment(1L, "pi_1"));
        WebhookResult canceled = result("pi_1", WebhookEventType.PAYMENT_FAILURE);
        when(strategy.checkPayment("pi_1")).thenReturn(canceled);
        when(handler.handleFailure(canceled, PendingPaymentReconciler.CANCELED_REASON)).thenReturn(true);

        reconciler.reconcile();

        verify(handler).handleFailure(canceled, PendingPaymentReconciler.CANCELED_REASON);
        assertThat(outcomes("failed")).isEqualTo(1);
    }

    @Test
    void aPaymentTheCustomerCanStillPayIsLeftAlone() {
        // Guards the reconciler touching a payment Stripe has not settled.
        due(payment(1L, "pi_1"));
        when(strategy.checkPayment("pi_1")).thenReturn(WebhookResult.unknown());

        reconciler.reconcile();

        verifyNoInteractions(handler);
        assertThat(outcomes("still_open")).isEqualTo(1);
    }

    @Test
    void aPaymentAnotherInstanceClaimedIsNotLookedUp() {
        // Guards two instances asking Stripe about one payment and applying the answer twice.
        due(payment(1L, "pi_1"));
        when(transactions.claimForReconciliation(eq(1L), any(), any())).thenReturn(0);

        reconciler.reconcile();

        verifyNoInteractions(strategy, handler);
    }

    @Test
    void oneFailedLookupDoesNotStopTheRun() {
        // Guards one payment Stripe cannot answer for holding back every other payment in the batch.
        due(payment(1L, "pi_1"), payment(2L, "pi_2"));
        when(strategy.checkPayment("pi_1")).thenThrow(new PaymentLookupException("timeout", null));
        when(strategy.checkPayment("pi_2")).thenReturn(WebhookResult.unknown());

        reconciler.reconcile();

        verify(strategy).checkPayment("pi_2");
        assertThat(outcomes("error")).isEqualTo(1);
        assertThat(outcomes("still_open")).isEqualTo(1);
    }

    @Test
    void threeFailedLookupsInARowStopTheRun() {
        // Guards a run that keeps calling Stripe while it is down or rate-limiting: the rest of the batch would
        // fail the same way, and each failure takes up to the full timeout.
        due(payment(1L, "pi_1"), payment(2L, "pi_2"), payment(3L, "pi_3"), payment(4L, "pi_4"));
        when(strategy.checkPayment(anyString())).thenThrow(new PaymentLookupException("down", null));

        reconciler.reconcile();

        verify(strategy, times(PendingPaymentReconciler.FAILURES_BEFORE_STOPPING)).checkPayment(anyString());
        verify(strategy, never()).checkPayment("pi_4");
    }

    @Test
    void theScanWindowAndBatchComeFromTheSettings() {
        // Guards the window being inverted, hard-coded or the gap between looks dropped, which would call Stripe for
        // every pending payment on every run, abandoned checkouts included. The values differ from the defaults, so
        // a reconciler that ignored the settings would fail here.
        properties.setMinAge(Duration.ofMinutes(2));
        properties.setMaxAge(Duration.ofHours(3));
        properties.setBatchSize(7);
        when(transactions.findReconcilable(any(), any(), any(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        reconciler.reconcile();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> createdBefore = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> createdAfter = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> lookedAtBefore = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).findReconcilable(
                createdBefore.capture(),
                createdAfter.capture(),
                lookedAtBefore.capture(),
                eq("reconcile:%"),
                page.capture()
        );
        assertThat(createdBefore.getValue())
                .isBetween(before.minus(Duration.ofMinutes(2)), after.minus(Duration.ofMinutes(2)));
        assertThat(createdAfter.getValue())
                .isBetween(before.minus(Duration.ofHours(3)), after.minus(Duration.ofHours(3)));
        assertThat(lookedAtBefore.getValue()).isEqualTo(createdBefore.getValue());
        assertThat(page.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    void anInterruptedRunStopsBeforeTheNextPayment() {
        // Guards shutdown waiting on a batch of Stripe calls that no one will see the end of. The interrupt arrives
        // during the first lookup, so a check made only once before the loop would not stop the second one.
        due(payment(1L, "pi_1"), payment(2L, "pi_2"));
        when(strategy.checkPayment("pi_1")).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return WebhookResult.unknown();
        });

        reconciler.reconcile();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(transactions, never()).claimForReconciliation(eq(2L), any(), any());
        verify(strategy, never()).checkPayment("pi_2");
    }

    @Test
    void failuresThatAreNotInARowDoNotStopTheRun() {
        // Guards the count of failures in a row never being reset, which would stop a run after three scattered
        // failures and leave the rest of the batch for the next run.
        due(payment(1L, "pi_1"), payment(2L, "pi_2"), payment(3L, "pi_3"), payment(4L, "pi_4"));
        when(strategy.checkPayment("pi_1")).thenThrow(new PaymentLookupException("timeout", null));
        when(strategy.checkPayment("pi_2")).thenReturn(WebhookResult.unknown());
        when(strategy.checkPayment("pi_3")).thenThrow(new PaymentLookupException("timeout", null));
        when(strategy.checkPayment("pi_4")).thenThrow(new PaymentLookupException("timeout", null));

        reconciler.reconcile();

        verify(strategy, times(4)).checkPayment(anyString());
    }

    @Test
    void aSuccessTheHandlerDoesNotApplyIsCountedAsUnchanged() {
        // Guards a payment the handler refused, for a different amount or because it already succeeded, being
        // reported as one the reconciler completed.
        due(payment(1L, "pi_1"));
        WebhookResult succeeded = result("pi_1", WebhookEventType.PAYMENT_SUCCESS);
        when(strategy.checkPayment("pi_1")).thenReturn(succeeded);
        when(handler.handleSuccess(succeeded)).thenReturn(false);

        reconciler.reconcile();

        assertThat(outcomes("unchanged")).isEqualTo(1);
        assertThat(outcomes("completed")).isZero();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void theLogNamesTheTransactionReferenceAndNeverTheUserId() {
        // Guards a user id reaching the logs (docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md).
        due(payment(1L, "pi_1"), payment(2L, "pi_2"));
        WebhookResult succeeded = result("pi_1", WebhookEventType.PAYMENT_SUCCESS);
        when(strategy.checkPayment("pi_1")).thenReturn(succeeded);
        when(handler.handleSuccess(succeeded)).thenReturn(true);
        when(strategy.checkPayment("pi_2")).thenThrow(new PaymentLookupException("timeout", null));

        reconciler.reconcile();

        assertThat(logs.list)
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain(USER_ID)
                        .containsPattern("ref-[12]"));
    }

    @Test
    void theSwitchTurnsTheReconcilerOff() {
        // Guards a typo in the switch's key, which would leave a documented setting without any effect.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(PaymentTransactionRepository.class, () -> transactions)
                .withBean(PaymentProviderFactory.class, () -> providers)
                .withBean(PaymentTransactionHandler.class, () -> handler)
                .withBean(PaymentReconciliationProperties.class, () -> properties)
                .withBean(MeterRegistry.class, () -> meters)
                .withUserConfiguration(PendingPaymentReconciler.class);

        runner.run(context -> assertThat(context).hasSingleBean(PendingPaymentReconciler.class));
        runner.withPropertyValues("payment.reconciliation.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PendingPaymentReconciler.class));
    }

    private void due(PaymentTransaction... payments) {
        when(transactions.findReconcilable(any(), any(), any(), any(), any())).thenReturn(List.of(payments));
    }

    private static PaymentTransaction payment(long id, String paymentIntentId) {
        return PaymentTransaction.builder()
                .id(id)
                .transactionReference("ref-" + id)
                .providerName("STRIPE")
                .providerTransactionId(paymentIntentId)
                .userId(USER_ID)
                .amount(new BigDecimal("50.0000"))
                .currency("USD")
                .status(TransactionStatus.PENDING)
                .build();
    }

    private static WebhookResult result(String paymentIntentId, WebhookEventType type) {
        return new WebhookResult(
                paymentIntentId,
                "reconcile:" + paymentIntentId,
                type,
                new BigDecimal("50.00"),
                "USD",
                Instant.parse("2026-10-04T12:00:00Z")
        );
    }

    private double outcomes(String outcome) {
        return meters.counter("payment.reconciliation.outcomes", "outcome", outcome).count();
    }
}
