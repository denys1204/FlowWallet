package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StepOutcomeTest {
    @Test
    void onlyAnAnswerThatSettledNothingIsAFailure() {
        // Guards a run stopped by definite answers, or one that keeps calling a Stripe that answers nothing.
        assertThat(new StepOutcome.RetryUnknown(Instant.EPOCH, "x").isFailure()).isTrue();
        assertThat(new StepOutcome.Attention(AttentionCause.UNKNOWN_LIMIT, false, "x").isFailure()).isTrue();
        assertThat(new StepOutcome.Attention(AttentionCause.DEFECT, false, "x").isFailure()).isFalse();
        assertThat(new StepOutcome.RetryRefused(Instant.EPOCH, "x").isFailure()).isFalse();
        assertThat(new StepOutcome.Released("x").isFailure()).isFalse();
        assertThat(new StepOutcome.Postponed(Instant.EPOCH, "x").isFailure()).isFalse();
    }

    @Test
    void everyMoveIntoReversingSharesOneMetricLabel() {
        // Guards a dashboard that misses reversals started from a found failed payout or a bank refusal.
        assertThat(new StepOutcome.FailedPayoutAdopted("po_1", null, null).label()).isEqualTo("reversing");
        assertThat(new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, null).label()).isEqualTo("reversing");
        assertThat(new StepOutcome.BankAccountRejected("x", 1L, null).label()).isEqualTo("reversing");
        assertThat(new StepOutcome.Postponed(Instant.EPOCH, "x").label()).isEqualTo("postponed");
    }

    @Test
    void aSpentKeyHasItsOwnLabelAndIsNoFailure() {
        // Guards a definite Stripe answer counted as a failure in a row, which stops a run, and a metric label that
        // drifts from the one the runbook names.
        StepOutcome spent = new StepOutcome.KeySpent("tr_1");

        assertThat(spent.label()).isEqualTo("key_spent");
        assertThat(spent.isFailure()).isFalse();
    }

    @Test
    void aPaidSettlementIsLabelledPaidAndIsNotAFailure() {
        // Guards a paid settlement counted under another label, or one that stops a run as if Stripe had not answered.
        StepOutcome paid = new StepOutcome.Paid();

        assertThat(paid.label()).isEqualTo("paid");
        assertThat(paid.isFailure()).isFalse();
    }
}
