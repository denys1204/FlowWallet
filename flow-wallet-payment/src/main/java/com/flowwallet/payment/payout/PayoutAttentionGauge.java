package com.flowwallet.payment.payout;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * How many payouts wait for an operator now, by the step they came from, apart from the hand-over counter
 * {@code payment.payouts.needs.attention}: a registry keeps one meter type per name. It reads the database each time
 * it is read, and exists whether or not the {@code payouts} endpoint is exposed.
 * See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
@Component
@RequiredArgsConstructor
public class PayoutAttentionGauge implements MeterBinder {
    private final PayoutStore store;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (PayoutStatus step : List.of(PayoutStatus.ACCEPTED, PayoutStatus.TRANSFERRED, PayoutStatus.REVERSING)) {
            Gauge.builder("payment.payouts.awaiting.operator", store, payouts -> payouts.countAwaitingOperator(step))
                    .description("Payouts in NEEDS_ATTENTION, by the step they came from")
                    .tag("from", step.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
    }
}
