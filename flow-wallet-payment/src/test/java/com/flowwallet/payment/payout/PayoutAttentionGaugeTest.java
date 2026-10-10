package com.flowwallet.payment.payout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static com.flowwallet.payment.payout.PayoutStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayoutAttentionGaugeTest {
    private static final String GAUGE = "payment.payouts.awaiting.operator";
    private static final String HAND_OVER_COUNTER = "payment.payouts.needs.attention";

    private final PayoutStore store = mock(PayoutStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PayoutAttentionGauge gauge = new PayoutAttentionGauge(store);

    private double reading(String from) {
        return registry.get(GAUGE).tag("from", from).gauge().value();
    }

    @Test
    void theGaugeCountsWaitingPayoutsByTheStepTheyCameFrom() {
        // Guards a gauge that mixes the steps or counts the wrong one: the alert reads them apart, because a payout
        // waiting from TRANSFERRED holds the money in a connected account and one from ACCEPTED leaves it on the
        // platform.
        when(store.countAwaitingOperator(ACCEPTED)).thenReturn(2L);
        when(store.countAwaitingOperator(TRANSFERRED)).thenReturn(5L);
        when(store.countAwaitingOperator(REVERSING)).thenReturn(7L);

        gauge.bindTo(registry);

        assertThat(registry.find(GAUGE).gauges())
                .extracting(meter -> meter.getId().getTag("from"))
                .containsExactlyInAnyOrder("accepted", "transferred", "reversing");
        assertThat(reading("accepted")).isEqualTo(2.0);
        assertThat(reading("transferred")).isEqualTo(5.0);
        assertThat(reading("reversing")).isEqualTo(7.0);
    }

    @Test
    void theGaugeAsksTheStoreEachTimeItIsRead() {
        // Guards a count taken once at startup: the gauge would keep reporting the payouts of the moment the service
        // started, after an operator had cleared them.
        when(store.countAwaitingOperator(ACCEPTED)).thenReturn(3L, 0L);

        gauge.bindTo(registry);

        assertThat(reading("accepted")).isEqualTo(3.0);
        assertThat(reading("accepted")).isEqualTo(0.0);
    }

    @Test
    void aFailingQueryReadsAsNotANumber() {
        // Guards a database outage that fails the read of the metrics endpoint: the gauge reports NaN for the step
        // and the other series stay readable.
        when(store.countAwaitingOperator(ACCEPTED)).thenThrow(new DataAccessResourceFailureException("database down"));
        when(store.countAwaitingOperator(TRANSFERRED)).thenReturn(1L);

        gauge.bindTo(registry);

        assertThat(reading("accepted")).isNaN();
        assertThat(reading("transferred")).isEqualTo(1.0);
    }

    @Test
    void theGaugeIsNamedApartFromTheHandOverCounter() {
        // Guards the gauge taking the counter's name: a registry keeps one meter type per name, so one of the two
        // would be refused or dropped, and either the hand-over rate or the waiting count would go missing.
        gauge.bindTo(registry);
        registry.counter(HAND_OVER_COUNTER, "from", "accepted", "cause", "unknown_limit").increment();

        assertThat(registry.find(GAUGE).meters()).hasSize(3).allMatch(meter -> meter instanceof Gauge);
        assertThat(registry.find(HAND_OVER_COUNTER).meters()).hasSize(1).allMatch(meter -> meter instanceof Counter);
        assertThat(registry.getMeters()).hasSize(4);
    }
}
