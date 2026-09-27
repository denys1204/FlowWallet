package com.flowwallet.wallet.balance;

import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.wallet.enums.RejectionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventTest {
    private static final Instant AT = Instant.parse("2026-09-05T12:00:00Z");

    private PaymentCompletedEvent completed(String amount) {
        return new PaymentCompletedEvent("evt-1", 1, "ref-1", "pi_1", new BigDecimal(amount), "USD", "alice", AT);
    }

    private PaymentFailedEvent failed(String amount) {
        return new PaymentFailedEvent(
                "evt-1", 1, "ref-1", "pi_1", new BigDecimal(amount), "USD", "alice", "card_declined", AT
        );
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0.00001", "1E+15"})
    void anAmountTheColumnCannotHoldExactlyIsStoredAsNull(String amount) {
        // NUMERIC(19,4) rounds 0.00001 to 0.0000 and refuses 1E+15. The first would record a figure the event
        // never carried; the second would fail the insert and send a readable refusal through the retries to the
        // dead-letter topic. The payload keeps the amount as sent.
        assertThat(ProcessedEvent.rejected(completed(amount), RejectionReason.INVALID_AMOUNT, "{}").getAmount())
                .isNull();
        assertThat(ProcessedEvent.failureRecorded(failed(amount), "{}").getAmount()).isNull();
    }

    @Test
    void anOffGridAmountTheColumnHoldsIsStoredAsSent() {
        // 10.123 USD is refused for its currency, yet the column holds it exactly, so the row keeps the figure
        // an operator totals refused money by.
        assertThat(ProcessedEvent.rejected(completed("10.123"), RejectionReason.INVALID_AMOUNT, "{}").getAmount())
                .isEqualByComparingTo("10.123");
    }
}
