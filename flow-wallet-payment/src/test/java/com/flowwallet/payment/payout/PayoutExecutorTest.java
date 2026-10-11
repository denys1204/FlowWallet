package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class PayoutExecutorTest {
    private static final String REF = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";

    /**
     * A fixed token, so a claim built twice is equal; the executor never compares it, the store does.
     */
    private static final Instant TOKEN = Instant.parse("2100-01-01T00:00:00Z");

    private final PayoutStore store = mock(PayoutStore.class);
    private final PayoutSteps steps = mock(PayoutSteps.class);
    private final PayoutProperties properties = new PayoutProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void clearTheInterrupt() {
        Thread.interrupted();
    }

    private PayoutExecutor executor() {
        return new PayoutExecutor(store, steps, properties, new StripeProperties(), meters);
    }

    private static ClaimedPayout claim(long id, PayoutStatus step) {
        return new ClaimedPayout(
                id,
                REF,
                step,
                new BigDecimal("75.0000"),
                "PLN",
                "acct_1",
                "tr_1",
                Instant.EPOCH,
                0,
                0,
                false,
                TOKEN,
                null
        );
    }

    private void due(int rows, StepOutcome outcome) {
        List<DuePayout> listed = new ArrayList<>();
        for (long id = 1; id <= rows; id++) {
            DuePayout row = new DuePayout(id, PayoutStatus.ACCEPTED);
            listed.add(row);
            ClaimedPayout claimed = claim(id, PayoutStatus.ACCEPTED);
            when(store.claim(eq(row), any(), any())).thenReturn(Optional.of(claimed));
            when(steps.work(claimed)).thenReturn(outcome);
            when(store.record(eq(claimed), any(), any())).thenReturn(RecordResult.RECORDED);
        }
        when(store.findDue(any(), anyInt())).thenReturn(listed);
    }

    private double counted(String step, String outcome) {
        return meters.counter("payment.payouts.executor.outcomes", "step", step, "outcome", outcome).count();
    }

    @Test
    void aRowClaimedElsewhereIsSkippedWithoutStripe() {
        // Guards a step run on a row another executor holds.
        DuePayout row = new DuePayout(1L, PayoutStatus.ACCEPTED);
        when(store.findDue(any(), anyInt())).thenReturn(List.of(row));
        when(store.claim(eq(row), any(), any())).thenReturn(Optional.empty());

        executor().run();

        verifyNoInteractions(steps);
        verify(store, never()).record(any(), any(), any());
    }

    @Test
    void theTokenAndTheLeaseEndAreWholeMicroseconds() {
        // Guards a token with nanoseconds the column cannot hold, which would never equal the value read back, so no
        // outcome could be recorded.
        DuePayout row = new DuePayout(1L, PayoutStatus.ACCEPTED);
        when(store.findDue(any(), anyInt())).thenReturn(List.of(row));
        when(store.claim(eq(row), any(), any())).thenReturn(Optional.empty());

        executor().run();

        ArgumentCaptor<Instant> claimedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> claimedUntil = ArgumentCaptor.forClass(Instant.class);
        verify(store).claim(eq(row), claimedAt.capture(), claimedUntil.capture());
        assertThat(claimedAt.getValue().getNano() % 1000).isZero();
        assertThat(claimedUntil.getValue().getNano() % 1000).isZero();
        assertThat(Duration.between(claimedAt.getValue(), claimedUntil.getValue())).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void theOutcomeIsRecordedForItsClaim() {
        // Guards an outcome recorded for another row or another claim than the one the step worked.
        StepOutcome transferred = new StepOutcome.Transferred("acct_1", "tr_1", false);
        due(1, transferred);

        executor().run();

        verify(store).record(eq(claim(1, PayoutStatus.ACCEPTED)), eq(transferred), any());
    }

    @Test
    void threeFailuresInARowStopTheRun() {
        // Guards a run that keeps calling a Stripe that is down for the rest of its batch.
        due(5, new StepOutcome.RetryUnknown(Instant.now(), "timeout"));

        executor().run();

        verify(store, times(3)).claim(any(), any(), any());
    }

    @Test
    void aDefiniteAnswerResetsTheFailureCount() {
        // Guards a run stopped by failures that were not in a row.
        List<DuePayout> rows = List.of(
                new DuePayout(1L, PayoutStatus.ACCEPTED),
                new DuePayout(2L, PayoutStatus.ACCEPTED),
                new DuePayout(3L, PayoutStatus.ACCEPTED),
                new DuePayout(4L, PayoutStatus.ACCEPTED),
                new DuePayout(5L, PayoutStatus.ACCEPTED)
        );
        when(store.findDue(any(), anyInt())).thenReturn(rows);
        StepOutcome unknown = new StepOutcome.RetryUnknown(Instant.now(), "timeout");
        List<StepOutcome> outcomes = List.of(unknown, unknown, new StepOutcome.Released("x"), unknown, unknown);
        for (int i = 0; i < rows.size(); i++) {
            ClaimedPayout claimed = claim(i + 1, PayoutStatus.ACCEPTED);
            when(store.claim(eq(rows.get(i)), any(), any())).thenReturn(Optional.of(claimed));
            when(steps.work(claimed)).thenReturn(outcomes.get(i));
            when(store.record(eq(claimed), any(), any())).thenReturn(RecordResult.RECORDED);
        }

        executor().run();

        verify(store, times(5)).claim(any(), any(), any());
    }

    @Test
    void aSpentRunBudgetStopsBeforeTheNextClaim() {
        // Guards a run that outlasts its budget and holds the scheduler thread.
        properties.getExecutor().setRunBudget(Duration.ofNanos(1));
        due(3, new StepOutcome.Released("x"));

        executor().run();

        verify(store, never()).claim(any(), any(), any());
    }

    @Test
    void anInterruptStopsTheRunBeforeTheNextClaim() {
        // Guards a shutdown delayed by Stripe calls started after the interrupt.
        due(3, new StepOutcome.Released("x"));
        Thread.currentThread().interrupt();

        executor().run();

        verify(store, never()).claim(any(), any(), any());
    }

    @Test
    void anUnknownOutcomeSeenWhileInterruptedIsPostponedByTheUnknownBackoff() {
        // Guards a shutdown counting a try against the limit when the call was cut short by the shutdown itself, and a
        // row due again at once while Stripe may still be running the cut-short request.
        due(1, new StepOutcome.Released("unused"));
        ClaimedPayout claimed = claim(1, PayoutStatus.ACCEPTED);
        when(steps.work(claimed)).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            return new StepOutcome.RetryUnknown(Instant.now(), "interrupted");
        });

        executor().run();

        ArgumentCaptor<StepOutcome> recorded = ArgumentCaptor.forClass(StepOutcome.class);
        verify(store).record(eq(claimed), recorded.capture(), any());
        assertThat(recorded.getValue()).isInstanceOfSatisfying(StepOutcome.Postponed.class, postponed ->
                assertThat(postponed.nextAttemptAt()).isAfter(Instant.now().plusSeconds(50)));
    }

    @Test
    void aDoneOutcomeIsRecordedEvenWhenInterrupted() {
        // Guards a transfer Stripe made going unrecorded because a shutdown began meanwhile.
        StepOutcome transferred = new StepOutcome.Transferred("acct_1", "tr_1", false);
        due(1, transferred);
        ClaimedPayout claimed = claim(1, PayoutStatus.ACCEPTED);
        when(steps.work(claimed)).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            return transferred;
        });

        executor().run();

        verify(store).record(eq(claimed), eq(transferred), any());
    }

    @Test
    void aFailedRecordIsCountedAndTheRunGoesOn() {
        // Guards one row's database error stopping the whole batch, or passing unseen.
        due(2, new StepOutcome.Released("x"));
        when(store.record(eq(claim(1, PayoutStatus.ACCEPTED)), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        executor().run();

        verify(store, times(2)).claim(any(), any(), any());
        assertThat(counted("transfer", "error")).isEqualTo(1.0);
    }

    @Test
    void aLostClaimIsLoggedAndCounted(CapturedOutput output) {
        // Guards two executors acting on one step unseen: a lapsed lease is a sizing problem to fix.
        due(1, new StepOutcome.Transferred("acct_1", "tr_1", false));
        when(store.record(any(), any(), any())).thenReturn(RecordResult.TAKEN_BY_CLAIM);

        executor().run();

        assertThat(counted("transfer", "claim_lost")).isEqualTo(1.0);
        assertThat(output.getAll()).contains("lost its claim to another executor");
    }

    @Test
    void attentionIsLoggedAtErrorAndCountedWithItsStepAndCause(CapturedOutput output) {
        // Guards a payout handed to an operator that no alert ever shows.
        due(1, new StepOutcome.Attention(
                AttentionCause.MULTIPLE,
                false,
                "2 transfers in the group not reversed in full"
        ));

        executor().run();

        assertThat(meters.counter("payment.payouts.needs.attention", "from", "accepted", "cause", "multiple").count())
                .isEqualTo(1.0);
        assertThat(output.getAll()).contains("ERROR").contains("Payout " + REF + " needs attention from ACCEPTED");
    }

    @Test
    void aSpentKeyIsLoggedAtWarnWithBothKeyGenerationsAndCounted(CapturedOutput output) {
        // Guards a silent change of the transfer key, which an operator comparing Stripe's request log with the row
        // could not trace.
        DuePayout row = new DuePayout(1L, PayoutStatus.ACCEPTED);
        ClaimedPayout claimed = new ClaimedPayout(
                1L,
                REF,
                PayoutStatus.ACCEPTED,
                new BigDecimal("75.0000"),
                "PLN",
                null,
                null,
                null,
                2,
                5,
                true,
                TOKEN,
                null
        );
        when(store.findDue(any(), anyInt())).thenReturn(List.of(row));
        when(store.claim(eq(row), any(), any())).thenReturn(Optional.of(claimed));
        when(steps.work(claimed)).thenReturn(new StepOutcome.KeySpent("tr_7"));
        when(store.record(eq(claimed), any(), any())).thenReturn(RecordResult.RECORDED);

        executor().run();

        assertThat(output.getAll())
                .contains("WARN")
                .contains("Payout " + REF + " transfer key generation 2 answers with transfer tr_7, reversed in full; "
                        + "the next try uses generation 3");
        assertThat(counted("transfer", "key_spent")).isEqualTo(1.0);
    }

    @Test
    void logLinesNameTheReferenceAndTheStripeIds(CapturedOutput output) {
        // Guards a log line an operator cannot match to the payout or to Stripe's dashboard.
        due(1, new StepOutcome.Transferred("acct_1", "tr_1", false));

        executor().run();

        assertThat(output.getAll()).contains("Payout " + REF + " transferred as tr_1");
        assertThat(counted("transfer", "transferred")).isEqualTo(1.0);
    }

    @Test
    void startupFailsWhenTheLeaseDoesNotCoverThreeStripeCalls() {
        // Guards a claim that lapses while its call still runs, letting a second executor act on the same step.
        properties.getExecutor().setLease(Duration.ofSeconds(30));

        assertThatThrownBy(this::executor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.payout.executor.lease");
    }

    @Test
    void theDefaultLeaseCoversThreeStripeCalls() {
        // Guards shipped defaults that stop every start.
        assertThatCode(this::executor).doesNotThrowAnyException();
    }

    @Test
    void theExecutorIsOffWhenDisabled() {
        // Guards a switch that leaves the executor running in a test that drives the steps itself.
        new ApplicationContextRunner()
                .withPropertyValues("payment.payout.executor.enabled=false")
                .withBean(PayoutStore.class, () -> store)
                .withBean(PayoutSteps.class, () -> steps)
                .withBean(PayoutProperties.class, () -> properties)
                .withBean(StripeProperties.class, StripeProperties::new)
                .withBean(SimpleMeterRegistry.class, () -> meters)
                .withUserConfiguration(PayoutExecutor.class)
                .run(context -> assertThat(context).doesNotHaveBean(PayoutExecutor.class));
    }
}
