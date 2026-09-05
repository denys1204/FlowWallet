package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.wallet.enums.RejectionReason;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentEventListenerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentEventHandler handler = mock(PaymentEventHandler.class);
    private final PaymentEventOutcomeStore outcomes = mock(PaymentEventOutcomeStore.class);
    private final PaymentEventListener listener = new PaymentEventListener(objectMapper, handler, outcomes);

    private static final String COMPLETED = KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED;

    private ConsumerRecord<String, String> record(String eventType, String json) {
        var consumerRecord = new ConsumerRecord<>(KafkaConstants.PAYMENT_EVENTS_TOPIC, 0, 0L, "ref-1", json);
        if (eventType != null) {
            consumerRecord.headers().add(KafkaConstants.HEADER_EVENT_TYPE,
                    eventType.getBytes(StandardCharsets.UTF_8));
        }
        return consumerRecord;
    }

    private ConsumerRecord<String, String> completed(String eventId, String amount, String currency, String userId) {
        return record(COMPLETED, objectMapper.writeValueAsString(new PaymentCompletedEvent(
                eventId, 1, "ref-1", "pi_1",
                amount == null ? null : new BigDecimal(amount), currency, userId,
                Instant.parse("2026-09-05T12:00:00Z")
        )));
    }

    @Test
    void aRecordWithNoTypeHeaderIsDeadLettered() {
        // The payload is not allowed to imply the type: guessing from the fields present breaks the first
        // time the schema grows one.
        assertThatThrownBy(() -> listener.onPaymentEvent(completedWithoutHeader()))
                .isInstanceOf(UnreadablePaymentEventException.class);

        verifyNoInteractions(handler, outcomes);
    }

    private ConsumerRecord<String, String> completedWithoutHeader() {
        return record(null, "{}");
    }

    @Test
    void anUnrecognisedTypeIsDeadLettered() {
        assertThatThrownBy(() -> listener.onPaymentEvent(record("PaymentReversedEvent", "{}")))
                .isInstanceOf(UnreadablePaymentEventException.class);

        verifyNoInteractions(handler);
    }

    @Test
    void anUnparseablePayloadIsDeadLettered() {
        assertThatThrownBy(() -> listener.onPaymentEvent(record(COMPLETED, "{not json")))
                .isInstanceOf(UnreadablePaymentEventException.class);

        verifyNoInteractions(handler);
    }

    @Test
    void anEventWithNoIdIsDeadLetteredRatherThanRecorded() {
        // Nothing to deduplicate on and nothing to key a row by, so there is no honest way to record it.
        assertThatThrownBy(() -> listener.onPaymentEvent(completed(null, "50.00", "USD", "alice")))
                .isInstanceOf(UnreadablePaymentEventException.class);

        verifyNoInteractions(handler, outcomes);
    }

    @Test
    void aNegativeAmountIsRefusedBeforeAnyTransactionOpens() {
        // The event records carry no validation annotations, so this is the only thing standing between a
        // negative amount and a wallet quietly being debited with a self-consistent ledger row behind it.
        listener.onPaymentEvent(completed("evt-1", "-500.00", "USD", "alice"));

        verify(outcomes).recordRejection(eq("evt-1"), eq(COMPLETED), eq("ref-1"), any(),
                eq(RejectionReason.INVALID_AMOUNT), any());
        verifyNoInteractions(handler);
    }

    @Test
    void aZeroAmountIsRefused() {
        listener.onPaymentEvent(completed("evt-1", "0.00", "USD", "alice"));

        verify(outcomes).recordRejection(any(), any(), any(), any(),
                eq(RejectionReason.INVALID_AMOUNT), any());
        verifyNoInteractions(handler);
    }

    @Test
    void aMissingUserIsRefusedBecauseTheWalletCannotBeResolvedWithoutOne() {
        listener.onPaymentEvent(completed("evt-1", "50.00", "USD", null));

        verify(outcomes).recordRejection(any(), any(), any(), any(),
                eq(RejectionReason.INVALID_ENVELOPE), any());
        verifyNoInteractions(handler);
    }

    @Test
    void aRedeliveryOfAProcessedEventIsAcknowledgedQuietly() {
        doThrow(new DataIntegrityViolationException("event_id")).when(handler).credit(any());
        when(outcomes.classify("evt-1", "ref-1")).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(completed("evt-1", "50.00", "USD", "alice"));

        verify(outcomes, never()).recordRejection(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aSecondEventForOneReferenceIsRecordedAsAContractViolation() {
        doThrow(new DataIntegrityViolationException("transaction_reference")).when(handler).credit(any());
        when(outcomes.classify("evt-2", "ref-1")).thenReturn(DuplicateVerdict.REFERENCE_ALREADY_CREDITED);

        listener.onPaymentEvent(completed("evt-2", "50.00", "USD", "alice"));

        verify(outcomes).recordRejection(eq("evt-2"), eq(COMPLETED), eq("ref-1"), any(),
                eq(RejectionReason.DUPLICATE_REFERENCE), any());
    }

    @Test
    void aRedeliveredRefusalIsNotDeadLettered() {
        // Recording a refusal meets the same event-id barrier as a credit. Without a guard the redelivery of
        // an event the wallet already refused would be dead-lettered, filling the topic meant for records the
        // wallet could not read with ones it read fine and deliberately declined.
        doThrow(new DataIntegrityViolationException("event_id"))
                .when(outcomes).recordRejection(any(), any(), any(), any(), any(), any());
        when(outcomes.classify("evt-1", "ref-1")).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(completed("evt-1", "-5.00", "USD", "alice"));

        verify(outcomes).classify("evt-1", "ref-1");
    }

    @Test
    void aRefusalThatFailsForAnotherReasonIsStillRaised() {
        DataIntegrityViolationException somethingElse = new DataIntegrityViolationException("value too long");
        doThrow(somethingElse).when(outcomes).recordRejection(any(), any(), any(), any(), any(), any());
        when(outcomes.classify(any(), any())).thenReturn(DuplicateVerdict.NOT_A_DUPLICATE);

        assertThatThrownBy(() -> listener.onPaymentEvent(completed("evt-1", "-5.00", "USD", "alice")))
                .isSameAs(somethingElse);
    }

    @Test
    void aViolationThatIsNeitherBarrierIsRaisedRatherThanAcknowledged() {
        // The most important test here. Treating an unrecognised violation as a duplicate would acknowledge
        // an event whose credit never happened, and Kafka would never redeliver it.
        DataIntegrityViolationException somethingElse = new DataIntegrityViolationException("value too long");
        doThrow(somethingElse).when(handler).credit(any());
        when(outcomes.classify(any(), any())).thenReturn(DuplicateVerdict.NOT_A_DUPLICATE);

        assertThatThrownBy(() -> listener.onPaymentEvent(completed("evt-1", "50.00", "USD", "alice")))
                .isSameAs(somethingElse);

        verify(outcomes, never()).recordRejection(any(), any(), any(), any(), any(), any());
    }
}
