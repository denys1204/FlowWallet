package com.flowwallet.payment.payout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PayoutReconcilerTest {
    private final PayoutStore store = mock(PayoutStore.class);
    private final PayoutSettler settler = mock(PayoutSettler.class);
    private final PayoutProperties properties = new PayoutProperties();
    private final PayoutProperties.Reconciler settings = properties.getReconciler();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PayoutReconciler reconciler = new PayoutReconciler(store, settler, properties, meters);
    private final Logger logger = (Logger) LoggerFactory.getLogger(PayoutReconciler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(store.claimForReconciliation(any(), any(), any())).thenReturn(true);
        when(settler.settle(anyString(), anyString())).thenReturn(settled(Settlement.Kind.REPEATED));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppenderAndClearInterrupt() {
        logger.detachAppender(logs);
        Thread.interrupted();
    }

    @Test
    void createdRowsComeFirstAndPaidRowsFillTheBatch() {
        // Guards a user waiting on a created payout being served after the rows that only need a recheck, and a run
        // that reads more than one batch: each row is one Stripe read.
        settings.setBatchSize(5);
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1), created(2)));
        when(store.findPaidToReconcile(any(), any(), anyInt())).thenReturn(List.of(paid(3), paid(4), paid(5)));

        reconciler.reconcile();

        verify(store).findCreatedToReconcile(any(), eq(5));
        verify(store).findPaidToReconcile(any(), any(), eq(3));
        InOrder order = inOrder(settler);
        order.verify(settler).settle("acct_1", "po_1");
        order.verify(settler).settle("acct_2", "po_2");
        order.verify(settler).settle("acct_3", "po_3");
        order.verify(settler).settle("acct_4", "po_4");
        order.verify(settler).settle("acct_5", "po_5");
    }

    @Test
    void aBatchFullOfCreatedRowsLeavesNoRoomForPaidRows() {
        // Guards a run that asks for a paid page of zero rows, or for a full one, after the created rows already used
        // the batch.
        settings.setBatchSize(2);
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1), created(2)));

        reconciler.reconcile();

        verify(store, never()).findPaidToReconcile(any(), any(), anyInt());
        verify(settler, times(2)).settle(anyString(), anyString());
    }

    @Test
    void paidRowsAreReadWhenNoCreatedRowIsDue() {
        // Guards a run that returns after an empty created page, which would leave a paid payout that fails days later
        // unseen whenever no payout is waiting for its first look.
        when(store.findPaidToReconcile(any(), any(), anyInt())).thenReturn(List.of(paid(1)));

        reconciler.reconcile();

        verify(store).findPaidToReconcile(any(), any(), eq(settings.getBatchSize()));
        verify(settler).settle("acct_1", "po_1");
    }

    @Test
    void theCutoffsComeFromTheProperties() {
        // Guards a window that is inverted, hard-coded or measured from the wrong end: the values differ from the
        // defaults, so a reconciler that ignored the settings would fail here.
        settings.setCreatedMinAge(Duration.ofMinutes(2));
        settings.setPaidRecheck(Duration.ofHours(3));
        settings.setPaidPeriod(Duration.ofDays(4));
        settings.setBatchSize(7);
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1), created(2)));
        Instant before = Instant.now();

        reconciler.reconcile();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> createdCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> paidAfter = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> paidCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(store).findCreatedToReconcile(createdCutoff.capture(), eq(7));
        verify(store).findPaidToReconcile(paidAfter.capture(), paidCutoff.capture(), eq(5));
        assertThat(createdCutoff.getValue())
                .isBetween(before.minus(Duration.ofMinutes(2)), after.minus(Duration.ofMinutes(2)));
        assertThat(paidAfter.getValue()).isBetween(before.minus(Duration.ofDays(4)), after.minus(Duration.ofDays(4)));
        assertThat(paidCutoff.getValue())
                .isBetween(before.minus(Duration.ofHours(3)), after.minus(Duration.ofHours(3)));
    }

    @Test
    void eachRowIsClaimedWithTheCutoffOfItsOwnStatus() {
        // Guards a paid row claimed against the created cutoff, or the reverse: the claim would then win a row that
        // was looked at too recently, or lose one that is due, and the gap between looks would be wrong.
        ReconcilablePayout created = created(1);
        ReconcilablePayout paid = paid(2);
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created));
        when(store.findPaidToReconcile(any(), any(), anyInt())).thenReturn(List.of(paid));
        Instant before = Instant.now();

        reconciler.reconcile();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> createdCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> paidCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(store).findCreatedToReconcile(createdCutoff.capture(), anyInt());
        verify(store).findPaidToReconcile(any(), paidCutoff.capture(), anyInt());
        ArgumentCaptor<Instant> claimedAt = ArgumentCaptor.forClass(Instant.class);
        verify(store).claimForReconciliation(eq(created), claimedAt.capture(), eq(createdCutoff.getValue()));
        verify(store).claimForReconciliation(eq(paid), claimedAt.capture(), eq(paidCutoff.getValue()));
        assertThat(claimedAt.getAllValues()).hasSize(2).allSatisfy(at -> assertThat(at).isBetween(before, after));
    }

    @Test
    void aLostClaimCostsNoStripeRead() {
        // Guards two instances asking Stripe about one payout and applying the answer twice, and a lost claim that
        // holds back the rows after it.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1), created(2)));
        when(store.claimForReconciliation(eq(created(1)), any(), any())).thenReturn(false);

        reconciler.reconcile();

        verify(settler, never()).settle("acct_1", "po_1");
        verify(settler).settle("acct_2", "po_2");
        assertThat(meters.getMeters()).hasSize(1);
        assertThat(counted("payout_created", "repeated")).isEqualTo(1.0);
    }

    @Test
    void threeFailuresInARowStopTheRun() {
        // Guards a run that keeps calling Stripe while it is down, refuses the key or rate-limits: the rest of the
        // batch would fail the same way, and each failure can take up to the full timeout.
        when(store.findCreatedToReconcile(any(), anyInt()))
                .thenReturn(List.of(created(1), created(2), created(3), created(4), created(5)));
        when(settler.settle(anyString(), anyString())).thenThrow(new PayoutLookupException("down", null));

        reconciler.reconcile();

        verify(settler, times(PayoutReconciler.FAILURES_BEFORE_STOPPING)).settle(anyString(), anyString());
        verify(settler, never()).settle("acct_4", "po_4");
        assertThat(counted("payout_created", "error")).isEqualTo(3.0);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("Payout reconciler stopped this run after 3 failed reads in a row");
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        // Guards a count of failures that is never reset, which would stop a run after three scattered failures and
        // leave the rest of the batch for the next run. The seventh row is the one three failures in a row skip.
        List<ReconcilablePayout> rows = List.of(
                created(1),
                created(2),
                created(3),
                created(4),
                created(5),
                created(6),
                created(7)
        );
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(rows);
        for (long id : new long[] {1, 2, 4, 5, 6}) {
            when(settler.settle("acct_" + id, "po_" + id)).thenThrow(new PayoutLookupException("timeout", null));
        }

        reconciler.reconcile();

        verify(settler, times(6)).settle(anyString(), anyString());
        verify(settler, never()).settle("acct_7", "po_7");
        assertThat(counted("payout_created", "error")).isEqualTo(5.0);
        assertThat(counted("payout_created", "repeated")).isEqualTo(1.0);
    }

    @Test
    void aFailedReadIsLoggedAtWarnWithTheReferenceAndCountedAsAnError() {
        // Guards a payout Stripe cannot answer for passing unseen, or a log line an operator cannot match to the
        // payout.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1)));
        when(settler.settle("acct_1", "po_1")).thenThrow(new PayoutLookupException("Stripe is down", null));

        reconciler.reconcile();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .startsWith("Payout reconciler could not check payout ref-1: ")
                    .contains("Stripe is down");
        });
        assertThat(counted("payout_created", "error")).isEqualTo(1.0);
    }

    @Test
    void aFailureInTheStoreIsAnErrorToo() {
        // Guards one row's database error stopping the whole batch, or passing unseen: any exception from the settle
        // counts, not only a failed Stripe read.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1), created(2)));
        when(settler.settle("acct_1", "po_1")).thenThrow(new IllegalStateException("db down"));

        reconciler.reconcile();

        verify(settler).settle("acct_2", "po_2");
        assertThat(counted("payout_created", "error")).isEqualTo(1.0);
        assertThat(counted("payout_created", "repeated")).isEqualTo(1.0);
    }

    @Test
    void aRunInterruptedBeforeTheFirstRowClaimsNothing() {
        // Guards a shutdown delayed by Stripe calls started after the interrupt.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1), created(2)));
        Thread.currentThread().interrupt();

        reconciler.reconcile();

        verify(store, never()).claimForReconciliation(any(), any(), any());
        verifyNoInteractions(settler);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("interrupted");
        });
    }

    @Test
    void anInterruptDuringARowStopsBeforeTheNextOne() {
        // Guards shutdown waiting on a batch of Stripe calls that no one will see the end of. The interrupt arrives
        // during the first read, so a check made only once before the loop would not stop the second one.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1), created(2)));
        when(settler.settle("acct_1", "po_1")).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return settled(Settlement.Kind.REPEATED);
        });

        reconciler.reconcile();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(store, never()).claimForReconciliation(eq(created(2)), any(), any());
        verify(settler, never()).settle("acct_2", "po_2");
        assertThat(counted("payout_created", "repeated")).isEqualTo(1.0);
    }

    @Test
    void aSpentRunBudgetStopsBeforeTheNextClaim() {
        // Guards a run that outlasts its budget, holding a scheduler thread and reading Stripe past it. The selection
        // takes longer than the budget, so the deadline has passed whatever the clock's resolution.
        settings.setRunBudget(Duration.ofNanos(1));
        when(store.findCreatedToReconcile(any(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(5);
            return List.of(created(1), created(2));
        });

        reconciler.reconcile();

        verify(store, never()).claimForReconciliation(any(), any(), any());
        verifyNoInteractions(settler);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("run budget");
        });
    }

    @Test
    void aMoveIsLoggedAndCounted() {
        // Guards a payout settled without its webhook that nobody can see: each one is a lost delivery, and a steady
        // stream points at the webhook setup. The counter keeps a created row apart from a paid one.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1)));
        when(store.findPaidToReconcile(any(), any(), anyInt())).thenReturn(List.of(paid(2)));
        when(settler.settle("acct_1", "po_1"))
                .thenReturn(moved("ref-1", PayoutStatus.PAYOUT_CREATED, PayoutStatus.PAID));
        when(settler.settle("acct_2", "po_2")).thenReturn(moved("ref-2", PayoutStatus.PAID, PayoutStatus.REVERSING));

        reconciler.reconcile();

        assertThat(logs.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Payout ref-1 settled (PAYOUT_CREATED to PAID) without its webhook",
                "Payout ref-2 settled (PAID to REVERSING) without its webhook"
        );
        assertThat(meters.getMeters()).hasSize(2);
        assertThat(counted("payout_created", "moved")).isEqualTo(1.0);
        assertThat(counted("paid", "moved")).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "{0} is counted as {1}")
    @CsvSource({
            "MOVED, moved",
            "REPEATED, repeated",
            "CONTRADICTED, contradicted",
            "DIFFERING_TERMS, differing_terms",
            "UNKNOWN_PAYOUT, unknown_payout"
    })
    void everySettlementKindIsCountedUnderItsOutcomeTag(Settlement.Kind kind, String outcome) {
        // Guards an outcome tag that drifts from the names the docs and alerts use.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1)));
        when(settler.settle("acct_1", "po_1")).thenReturn(settled(kind));

        reconciler.reconcile();

        assertThat(meters.getMeters()).hasSize(1);
        assertThat(counted("payout_created", outcome)).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "{0} is not logged by the reconciler")
    @EnumSource(value = Settlement.Kind.class, names = "MOVED", mode = EnumSource.Mode.EXCLUDE)
    void aSettlementThatMovedNothingIsCountedButNotLogged(Settlement.Kind kind) {
        // Guards a warning for every payout the webhook already settled: a repeat is the normal case, and PayoutSettler
        // already logs the other kinds at their own level.
        when(store.findCreatedToReconcile(any(), anyInt())).thenReturn(List.of(created(1)));
        when(settler.settle("acct_1", "po_1")).thenReturn(settled(kind));

        reconciler.reconcile();

        assertThat(logs.list).isEmpty();
        assertThat(meters.getMeters()).hasSize(1);
    }

    @Test
    void theReconcilerRunsUnlessTheSwitchIsOff() {
        // Guards a typo in the switch's key, which would leave a documented setting without any effect, and a default
        // that starts the reconciler off.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(PayoutStore.class, () -> store)
                .withBean(PayoutSettler.class, () -> settler)
                .withBean(PayoutProperties.class, () -> properties)
                .withBean(MeterRegistry.class, () -> meters)
                .withUserConfiguration(PayoutReconciler.class);

        runner.run(context -> assertThat(context).hasSingleBean(PayoutReconciler.class));
        runner.withPropertyValues("payment.payout.reconciler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PayoutReconciler.class));
    }

    private static ReconcilablePayout created(long id) {
        return new ReconcilablePayout(id, "ref-" + id, PayoutStatus.PAYOUT_CREATED, "acct_" + id, "po_" + id);
    }

    private static ReconcilablePayout paid(long id) {
        return new ReconcilablePayout(id, "ref-" + id, PayoutStatus.PAID, "acct_" + id, "po_" + id);
    }

    private static Settlement settled(Settlement.Kind kind) {
        return new Settlement(
                kind,
                "ref-1",
                ProviderPayoutStatus.IN_TRANSIT,
                PayoutStatus.PAYOUT_CREATED,
                PayoutStatus.PAYOUT_CREATED,
                false,
                null
        );
    }

    private static Settlement moved(String reference, PayoutStatus from, PayoutStatus to) {
        return new Settlement(Settlement.Kind.MOVED, reference, ProviderPayoutStatus.PAID, from, to, false, null);
    }

    private double counted(String status, String outcome) {
        return meters.counter("payment.payouts.reconciler.outcomes", "status", status, "outcome", outcome).count();
    }
}
