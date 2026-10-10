package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static com.flowwallet.payment.payout.PayoutAction.*;
import static com.flowwallet.payment.payout.PayoutStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PayoutActionTest {
    @ParameterizedTest
    @CsvSource({
            "requeue, REQUEUE",
            "return-without-reversal, RETURN_WITHOUT_REVERSAL",
            "reverse, REVERSE"
    })
    void aPathNamesItsActionExactly(String path, PayoutAction action) {
        // Guards a path that stops matching its action, which would answer 400 for an action the operator was told
        // to use, or an action whose path changed without the endpoint's callers.
        assertThat(PayoutAction.of(path)).contains(action);
        assertThat(action.path()).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "Requeue",
            "REQUEUE",
            "requeue ",
            " reverse",
            "return_without_reversal",
            "RETURN_WITHOUT_REVERSAL",
            "return",
            "reverse/",
            "refund"
    })
    void anythingButTheExactPathIsNoAction(String path) {
        // Guards a lenient match, such as a case fold, a trim, a prefix or the metric or enum name: a word the
        // documentation never offered would then move a payout's money.
        assertThat(PayoutAction.of(path)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "REQUEUE, requeue",
            "RETURN_WITHOUT_REVERSAL, return_without_reversal",
            "REVERSE, reverse"
    })
    void eachActionHasItsOwnMetricName(PayoutAction action, String metricName) {
        // Guards a renamed tag value, which would split the counter's series and leave an alert on the old one
        // silent.
        assertThat(action.metricName()).isEqualTo(metricName);
    }

    static Stream<Arguments> actionsByStep() {
        return Stream.of(
                arguments(ACCEPTED, List.of(REQUEUE, RETURN_WITHOUT_REVERSAL)),
                arguments(TRANSFERRED, List.of(REQUEUE, REVERSE)),
                arguments(REVERSING, List.of(REQUEUE, RETURN_WITHOUT_REVERSAL))
        );
    }

    @ParameterizedTest(name = "waiting from {0}")
    @MethodSource("actionsByStep")
    void aWaitingPayoutOffersTheActionsItsStepAllows(PayoutStatus from, List<PayoutAction> expected) {
        // Guards a return offered from TRANSFERRED, whose transfer a reversal can still bring back, and a reversal
        // offered from a step that holds no transfer or is already reversing.
        assertThat(PayoutAction.allowedFrom(from)).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @EnumSource(value = PayoutStatus.class, names = {"PAYOUT_CREATED", "PAID", "RETURNED", "NEEDS_ATTENTION"})
    void aStatusThatIsNotAStepOfTheExecutorOffersNoAction(PayoutStatus from) {
        // Guards an action offered for a payout that never waited from a step: nothing could carry it out.
        assertThat(PayoutAction.allowedFrom(from)).isEmpty();
    }

    @Test
    void aPayoutThatIsNotWaitingOffersNoAction() {
        // Guards a view that fails on a row that is not waiting: it asks for the actions of every payout it shows,
        // and such a row has no step.
        assertThat(PayoutAction.allowedFrom(null)).isEmpty();
    }
}
