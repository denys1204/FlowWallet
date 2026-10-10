package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import com.flowwallet.outbox.Outbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PayoutOutboxServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final Outbox outbox = mock(Outbox.class);
    private final PayoutOutboxService service = new PayoutOutboxService(outbox);

    @Test
    void aReturnIsQueuedUnderTheReferenceAndTheReturnedEventTypeWithItsReason() {
        // Guards the one payout event that moves money: the wallet credits on the header, keys by the reference and
        // binds the amount to the withdrawal, so any of them wrong credits the wrong thing or nothing.
        Payout payout = Payout.accepted("ref-1", "alice", new BigDecimal("75.0000"), "PLN", NOW);
        payout.returnBeforeTransfer(PayoutReason.TERMS_REFUSED, NOW);

        service.publishReturned(payout, NOW);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(
                eq(PayoutOutboxService.AGGREGATE_TYPE_PAYOUT),
                eq("ref-1"),
                eq(KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED),
                event.capture()
        );
        assertThat(event.getValue()).isInstanceOfSatisfying(PayoutReturnedEvent.class, returned -> {
            assertThat(returned.eventId()).isNotBlank();
            assertThat(returned.schemaVersion()).isEqualTo(KafkaConstants.PAYOUT_EVENT_SCHEMA_VERSION);
            assertThat(returned.transactionReference()).isEqualTo("ref-1");
            assertThat(returned.providerPayoutId()).isNull();
            assertThat(returned.amount()).isEqualByComparingTo("75.00");
            assertThat(returned.currency()).isEqualTo("PLN");
            assertThat(returned.userId()).isEqualTo("alice");
            assertThat(returned.reasonCode()).isEqualTo("TERMS_REFUSED");
            assertThat(returned.returnedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void theFailedEventCarriesTheReasonTheFailureCodeAndANullPayoutIdWithoutAPayout() {
        // Guards the wallet showing RETURN_PENDING with no reason, or with a payout id for a payout never made.
        Payout payout = Payout.accepted("ref-1", "alice", new BigDecimal("75.0000"), "PLN", NOW);
        payout.recordTransfer("acct_1", "tr_1", NOW);
        payout.startReversal(PayoutReason.BANK_ACCOUNT_NOT_SET, null, NOW);

        service.publishFailed(payout, NOW);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(
                eq(PayoutOutboxService.AGGREGATE_TYPE_PAYOUT),
                eq("ref-1"),
                eq(KafkaConstants.EVENT_TYPE_PAYOUT_FAILED),
                event.capture()
        );
        assertThat(event.getValue()).isInstanceOfSatisfying(PayoutFailedEvent.class, failed -> {
            assertThat(failed.eventId()).isNotBlank();
            assertThat(failed.providerPayoutId()).isNull();
            assertThat(failed.reasonCode()).isEqualTo("BANK_ACCOUNT_NOT_SET");
            assertThat(failed.providerFailureCode()).isNull();
            assertThat(failed.amount()).isEqualByComparingTo("75.00");
            assertThat(failed.failedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void thePaidEventCarriesThePayoutIdTheReferenceTheAmountTheCurrencyAndPaidAt() {
        // Guards the wallet showing a withdrawal as paid with no payout id, which its consumer refuses, under another
        // reference, or with a paid time that is not the one recorded on the payout.
        Instant paidAt = NOW.plusSeconds(300);
        Payout payout = Payout.accepted("ref-1", "alice", new BigDecimal("75.0000"), "PLN", NOW);
        payout.recordTransfer("acct_1", "tr_1", NOW);
        payout.recordPayout("po_1", NOW);
        payout.markPaid(paidAt);

        service.publishPaid(payout, paidAt);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(
                eq(PayoutOutboxService.AGGREGATE_TYPE_PAYOUT),
                eq("ref-1"),
                eq(KafkaConstants.EVENT_TYPE_PAYOUT_PAID),
                event.capture()
        );
        assertThat(event.getValue()).isInstanceOfSatisfying(PayoutPaidEvent.class, paid -> {
            assertThat(paid.eventId()).isNotBlank();
            assertThat(paid.schemaVersion()).isEqualTo(KafkaConstants.PAYOUT_EVENT_SCHEMA_VERSION);
            assertThat(paid.transactionReference()).isEqualTo("ref-1");
            assertThat(paid.providerPayoutId()).isEqualTo("po_1");
            assertThat(paid.amount()).isEqualByComparingTo("75.00");
            assertThat(paid.currency()).isEqualTo("PLN");
            assertThat(paid.userId()).isEqualTo("alice");
            assertThat(paid.paidAt()).isEqualTo(paidAt);
        });
    }

    @Test
    void eachPaidEventGetsItsOwnEventId() {
        // Guards a constant or reused event id, which the wallet's consumer would treat as a redelivery and drop.
        Payout payout = Payout.accepted("ref-1", "alice", new BigDecimal("75.0000"), "PLN", NOW);
        payout.recordTransfer("acct_1", "tr_1", NOW);
        payout.recordPayout("po_1", NOW);
        payout.markPaid(NOW);

        service.publishPaid(payout, NOW);
        service.publishPaid(payout, NOW);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(2)).enqueue(
                eq(PayoutOutboxService.AGGREGATE_TYPE_PAYOUT),
                eq("ref-1"),
                eq(KafkaConstants.EVENT_TYPE_PAYOUT_PAID),
                events.capture()
        );
        assertThat(events.getAllValues())
                .extracting(value -> ((PayoutPaidEvent) value).eventId())
                .doesNotHaveDuplicates();
    }
}
