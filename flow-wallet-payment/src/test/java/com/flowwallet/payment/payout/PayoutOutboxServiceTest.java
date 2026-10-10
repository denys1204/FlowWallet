package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import com.flowwallet.outbox.Outbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}
