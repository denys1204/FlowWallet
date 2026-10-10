package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {
    private static final Instant AT = Instant.parse("2026-10-10T12:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("75.00");

    @Test
    void eachEventKeepsItsOwnTypeAndFields() {
        // Guards a copied factory labelling every row of one event type with another's, which an operator reads to
        // tell a credited return from a recorded status, and a field taken from the wrong component.
        EventEnvelope deposit = EventEnvelope.of(
                new PaymentCompletedEvent("evt-0", 1, "ref-0", "pi_0", AMOUNT, "USD", "bob", AT)
        );
        EventEnvelope paid = EventEnvelope.of(
                new PayoutPaidEvent("evt-1", 1, "ref-1", "po_1", AMOUNT, "PLN", "alice", AT)
        );
        EventEnvelope failed = EventEnvelope.of(
                new PayoutFailedEvent("evt-2", 1, "ref-2", null, AMOUNT, "PLN", "alice", "PAYOUT_FAILED", null, AT)
        );
        EventEnvelope returned = EventEnvelope.of(
                new PayoutReturnedEvent("evt-3", 1, "ref-3", null, AMOUNT, "PLN", "alice", "PAYOUT_FAILED", AT)
        );

        assertThat(deposit).isEqualTo(new EventEnvelope(
                "evt-0", KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED, "ref-0", "bob", "USD", AMOUNT
        ));
        assertThat(paid).isEqualTo(new EventEnvelope(
                "evt-1", KafkaConstants.EVENT_TYPE_PAYOUT_PAID, "ref-1", "alice", "PLN", AMOUNT
        ));
        assertThat(failed).isEqualTo(new EventEnvelope(
                "evt-2", KafkaConstants.EVENT_TYPE_PAYOUT_FAILED, "ref-2", "alice", "PLN", AMOUNT
        ));
        assertThat(returned).isEqualTo(new EventEnvelope(
                "evt-3", KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED, "ref-3", "alice", "PLN", AMOUNT
        ));
    }
}
