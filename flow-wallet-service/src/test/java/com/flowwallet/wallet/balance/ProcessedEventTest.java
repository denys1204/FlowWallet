package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.wallet.enums.ProcessedEventOutcome;
import com.flowwallet.wallet.enums.RejectionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventTest {
    private static final Instant AT = Instant.parse("2026-09-05T12:00:00Z");

    private PaymentCompletedEvent completed(String amount) {
        return new PaymentCompletedEvent("evt-1", 1, "ref-1", "pi_1", new BigDecimal(amount), "USD", "alice", AT);
    }

    private PaymentFailedEvent failed(String amount) {
        return new PaymentFailedEvent(
                "evt-1",
                1,
                "ref-1",
                "pi_1",
                new BigDecimal(amount),
                "USD",
                "alice",
                "card_declined",
                AT
        );
    }

    private static ProcessedEvent rejected(PaymentCompletedEvent event) {
        return ProcessedEvent.rejected(EventEnvelope.of(event), RejectionReason.INVALID_AMOUNT, "{}");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0.00001", "1E+15"})
    void anAmountTheColumnCannotHoldExactlyIsStoredAsNull(String amount) {
        // NUMERIC(19,4) rounds 0.00001 to 0.0000 and refuses 1E+15. The first would record a figure the event
        // never carried; the second would fail the insert and send a readable refusal through the retries to the
        // dead-letter topic. The payload keeps the amount as sent.
        assertThat(rejected(completed(amount)).getAmount()).isNull();
        assertThat(ProcessedEvent.failureRecorded(failed(amount), "{}").getAmount()).isNull();
    }

    @Test
    void anOffGridAmountTheColumnHoldsIsStoredAsSent() {
        // 10.123 USD is refused for its currency, yet the column holds it exactly, so the row keeps the figure
        // an operator totals refused money by.
        assertThat(rejected(completed("10.123")).getAmount()).isEqualByComparingTo("10.123");
    }

    @Test
    void aReturnAndAStatusAreStoredUnderTheEventsOwnIdTypeAndReference() {
        // Guards a payout event's row taking another event's type, which an operator reads to tell a credited
        // return from a recorded status, and a status row losing the payload that names the payout and the reason.
        EventEnvelope returned = new EventEnvelope(
                "evt-3", KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED, "ref-1", "alice", "PLN", new BigDecimal("75.00")
        );
        EventEnvelope paid = new EventEnvelope(
                "evt-4", KafkaConstants.EVENT_TYPE_PAYOUT_PAID, "ref-1", "alice", "PLN", new BigDecimal("75.00")
        );

        ProcessedEvent credited = ProcessedEvent.returnCredited(returned);
        ProcessedEvent recorded = ProcessedEvent.statusRecorded(paid, "{\"eventId\":\"evt-4\"}");

        assertThat(credited.getEventType()).isEqualTo(KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED);
        assertThat(credited.getOutcome()).isEqualTo(ProcessedEventOutcome.RETURN_CREDITED);
        assertThat(credited.getPayload()).isNull();
        assertThat(recorded.getEventType()).isEqualTo(KafkaConstants.EVENT_TYPE_PAYOUT_PAID);
        assertThat(recorded.getOutcome()).isEqualTo(ProcessedEventOutcome.STATUS_RECORDED);
        assertThat(recorded.getPayload()).isEqualTo("{\"eventId\":\"evt-4\"}");
        assertThat(List.of(credited, recorded))
                .extracting(ProcessedEvent::getTransactionReference)
                .containsOnly("ref-1");
    }

    @Test
    void everyOutcomeAndRejectionReasonFitsItsColumn() {
        // Guards a constant added later that the VARCHAR(20) outcome or rejection_reason column would refuse,
        // which would send a readable event through the retries to the dead-letter topic.
        assertThat(Arrays.stream(ProcessedEventOutcome.values()).map(Enum::name)).allMatch(name -> name.length() <= 20);
        assertThat(Arrays.stream(RejectionReason.values()).map(Enum::name)).allMatch(name -> name.length() <= 20);
    }
}
