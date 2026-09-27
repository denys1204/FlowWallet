package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.wallet.enums.RejectionReason;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentEventListenerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentEventHandler handler = mock(PaymentEventHandler.class);
    private final PaymentEventOutcomeStore outcomes = mock(PaymentEventOutcomeStore.class);
    private final PaymentEventListener listener = new PaymentEventListener(objectMapper, handler, outcomes);

    private static final String COMPLETED = KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED;
    private static final String FAILED = KafkaConstants.EVENT_TYPE_PAYMENT_FAILED;

    private ConsumerRecord<String, String> record(String eventType, String json) {
        var consumerRecord = new ConsumerRecord<>(KafkaConstants.PAYMENT_EVENTS_TOPIC, 0, 0L, "ref-1", json);
        if (eventType != null) {
            consumerRecord.headers().add(KafkaConstants.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
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

    private ConsumerRecord<String, String> failed(String eventId) {
        return record(FAILED, objectMapper.writeValueAsString(new PaymentFailedEvent(
                eventId, 1, "ref-1", "pi_1",
                new BigDecimal("50.00"), "USD", "alice", "card_declined",
                Instant.parse("2026-09-05T12:00:00Z")
        )));
    }

    private static PaymentCompletedEvent eventWithId(String eventId) {
        return argThat(event -> eventId.equals(event.eventId()) && "ref-1".equals(event.transactionReference()));
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
        // The event records carry no validation annotations, so this is the only check that refuses a negative
        // amount as INVALID_AMOUNT with its payload kept. Without it the credit would reach
        // balance_history_amount_positive, roll back, and be retried and dead-lettered instead.
        listener.onPaymentEvent(completed("evt-1", "-500.00", "USD", "alice"));

        verify(outcomes).recordRejection(eventWithId("evt-1"), eq(RejectionReason.INVALID_AMOUNT), any());
        verifyNoInteractions(handler);
    }

    @Test
    void aZeroAmountIsRefused() {
        listener.onPaymentEvent(completed("evt-1", "0.00", "USD", "alice"));

        verify(outcomes).recordRejection(any(), eq(RejectionReason.INVALID_AMOUNT), any());
        verifyNoInteractions(handler);
    }

    @Test
    void aMissingUserIsRefusedBecauseTheWalletCannotBeResolvedWithoutOne() {
        listener.onPaymentEvent(completed("evt-1", "50.00", "USD", null));

        verify(outcomes).recordRejection(any(), eq(RejectionReason.INVALID_ENVELOPE), any());
        verifyNoInteractions(handler);
    }

    @Test
    void aRedeliveryOfAProcessedEventIsAcknowledgedQuietly() {
        doThrow(new DataIntegrityViolationException("event_id")).when(handler).credit(any());
        when(outcomes.classify("evt-1", "ref-1")).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(completed("evt-1", "50.00", "USD", "alice"));

        verify(outcomes, never()).recordRejection(any(), any(), any());
    }

    @Test
    void aSecondEventForOneReferenceIsRecordedAsAContractViolation() {
        doThrow(new DataIntegrityViolationException("transaction_reference")).when(handler).credit(any());
        when(outcomes.classify("evt-2", "ref-1")).thenReturn(DuplicateVerdict.REFERENCE_ALREADY_CREDITED);

        listener.onPaymentEvent(completed("evt-2", "50.00", "USD", "alice"));

        verify(outcomes).recordRejection(eventWithId("evt-2"), eq(RejectionReason.DUPLICATE_REFERENCE), any());
    }

    @Test
    void aPaymentForAWalletThatDoesNotExistIsRecordedRatherThanDeadLettered() {
        // The payload is kept so the event can be replayed once the wallet exists. Dead-lettering it would
        // tie recovery to Kafka retention instead.
        doThrow(new UnknownWalletException("alice", "USD")).when(handler).credit(any());

        listener.onPaymentEvent(completed("evt-1", "50.00", "USD", "alice"));

        verify(outcomes).recordRejection(eventWithId("evt-1"), eq(RejectionReason.WALLET_NOT_FOUND), any());
    }

    @Test
    void aRedeliveredRefusalIsNotDeadLettered() {
        // Recording a refusal meets the same event-id barrier as a credit. Without a guard the redelivery of
        // an event the wallet already refused would be dead-lettered, filling the topic meant for records the
        // wallet could not read or settle with ones it read fine and deliberately declined.
        doThrow(new DataIntegrityViolationException("event_id"))
                .when(outcomes).recordRejection(any(), any(), any());
        when(outcomes.classify("evt-1", "ref-1")).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(completed("evt-1", "-5.00", "USD", "alice"));

        verify(outcomes).classify("evt-1", "ref-1");
    }

    @Test
    void aRefusalThatFailsForAnotherReasonIsStillRaised() {
        DataIntegrityViolationException somethingElse = new DataIntegrityViolationException("value too long");
        doThrow(somethingElse).when(outcomes).recordRejection(any(), any(), any());
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

        verify(outcomes, never()).recordRejection(any(), any(), any());
    }

    @Test
    void aFailureEventIsRecordedWithItsPayload() {
        // The payload carries the failure reason, which the row keeps. A failure that reached the wallet's
        // refusal path instead would be stored as a rejected completion.
        ConsumerRecord<String, String> failure = failed("evt-9");

        listener.onPaymentEvent(failure);

        verify(handler).recordFailure(argThat(event -> "evt-9".equals(event.eventId())), eq(failure.value()));
        verify(handler, never()).credit(any());
        verifyNoInteractions(outcomes);
    }

    @Test
    void aRedeliveredFailureEventIsAcknowledged() {
        // Kafka delivers at least once, so the second copy of a recorded failure meets the event-id barrier.
        // Raising it would retry and dead-letter an event the wallet has already recorded.
        doThrow(new DataIntegrityViolationException("event_id")).when(handler).recordFailure(any(), any());
        when(outcomes.classify("evt-9", "ref-1")).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(failed("evt-9"));

        verify(outcomes).classify("evt-9", "ref-1");
        verify(outcomes, never()).recordRejection(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DuplicateVerdict.class, names = {"REFERENCE_ALREADY_CREDITED", "NOT_A_DUPLICATE"})
    void aFailureEventViolationThatIsNotARedeliveryIsRaised(DuplicateVerdict verdict) {
        // Only the event-id barrier makes a failed write safe to acknowledge. A failure moves no money, so a
        // DEPOSIT row under the reference says nothing about this event; acknowledging on it would drop a
        // failure the wallet never recorded.
        DataIntegrityViolationException violation = new DataIntegrityViolationException("value too long");
        doThrow(violation).when(handler).recordFailure(any(), any());
        when(outcomes.classify("evt-9", "ref-1")).thenReturn(verdict);

        assertThatThrownBy(() -> listener.onPaymentEvent(failed("evt-9"))).isSameAs(violation);
    }
}
