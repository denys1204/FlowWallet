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
        assertThat(new StepOutcome.FailedPayoutAdopted("po_1", null).label()).isEqualTo("reversing");
        assertThat(new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, null).label()).isEqualTo("reversing");
        assertThat(new StepOutcome.BankAccountRejected("x", 1L, null).label()).isEqualTo("reversing");
        assertThat(new StepOutcome.Postponed(Instant.EPOCH, "x").label()).isEqualTo("postponed");
    }
}
