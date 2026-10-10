package com.flowwallet.payment.outbox;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.outbox.Outbox;
import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.transaction.PaymentTransaction;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentOutboxServiceTest {
    private static final Instant SETTLED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private Outbox outbox;
    private PaymentOutboxService service;

    @BeforeEach
    void setUp() {
        outbox = mock(Outbox.class);
        service = new PaymentOutboxService(outbox, new PaymentEventMapperImpl());
    }

    @Test
    void aCompletedPaymentIsQueuedUnderTheCompletedEventTypeWithACompletedPayload() {
        // The wallet credits on the eventType header alone, so the header and the payload must name the same event.
        service.publishPaymentCompleted(transaction(), SETTLED_AT);

        Object event = queued(KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED);
        assertThat(event).isInstanceOfSatisfying(PaymentCompletedEvent.class, completed -> {
            assertThat(completed.transactionReference()).isEqualTo("ref-1");
            assertThat(completed.completedAt()).isEqualTo(SETTLED_AT);
        });
    }

    @Test
    void aFailedPaymentIsQueuedUnderTheFailedEventTypeWithAFailedPayload() {
        // A completed header on this row would make the wallet credit a payment that failed.
        service.publishPaymentFailed(transaction(), "card declined", SETTLED_AT);

        Object event = queued(KafkaConstants.EVENT_TYPE_PAYMENT_FAILED);
        assertThat(event).isInstanceOfSatisfying(PaymentFailedEvent.class, failed -> {
            assertThat(failed.transactionReference()).isEqualTo("ref-1");
            assertThat(failed.reason()).isEqualTo("card declined");
        });
    }

    /**
     * Captures the event queued under the payment's aggregate type and reference, the record key that keeps one
     * payment's events on one partition.
     */
    private Object queued(String eventType) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(
                eq(PaymentOutboxService.AGGREGATE_TYPE_PAYMENT_TRANSACTION),
                eq("ref-1"),
                eq(eventType),
                event.capture()
        );
        return event.getValue();
    }

    private PaymentTransaction transaction() {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                "ref-1",
                new BigDecimal("50.00"),
                "USD",
                "STRIPE"
        );
        return PaymentTransaction.create(request, "user-1", PaymentProvider.STRIPE);
    }
}
