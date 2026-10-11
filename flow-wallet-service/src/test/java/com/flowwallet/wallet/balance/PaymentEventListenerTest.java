package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import com.flowwallet.wallet.enums.RejectionReason;
import com.flowwallet.wallet.enums.TransactionType;
import com.flowwallet.wallet.withdrawal.UnknownWithdrawalException;
import com.flowwallet.wallet.withdrawal.WithdrawalEventHandler;
import com.flowwallet.wallet.withdrawal.WithdrawalMismatchException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentEventListenerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentEventHandler handler = mock(PaymentEventHandler.class);
    private final WithdrawalEventHandler withdrawalHandler = mock(WithdrawalEventHandler.class);
    private final PaymentEventOutcomeStore outcomes = mock(PaymentEventOutcomeStore.class);
    private final PaymentEventListener listener =
            new PaymentEventListener(objectMapper, handler, withdrawalHandler, outcomes);

    private static final String COMPLETED = KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED;
    private static final String FAILED = KafkaConstants.EVENT_TYPE_PAYMENT_FAILED;
    private static final String PAYOUT_PAID = KafkaConstants.EVENT_TYPE_PAYOUT_PAID;
    private static final String PAYOUT_FAILED = KafkaConstants.EVENT_TYPE_PAYOUT_FAILED;
    private static final String PAYOUT_RETURNED = KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED;
    private static final Instant AT = Instant.parse("2026-10-10T12:00:00Z");
    private static final String QUOTED_USER = "c7e4a1b2-5d3f-4e6a-8b9c-0d1e2f3a4b5c";
    // Jackson quotes an unquoted token up to the first character that cannot be part of a Java identifier.
    private static final String QUOTED_USER_PREFIX = "c7e4a1b2";

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

    private ConsumerRecord<String, String> payoutPaid(String eventId, String providerPayoutId) {
        return record(PAYOUT_PAID, objectMapper.writeValueAsString(new PayoutPaidEvent(
                eventId, 1, "ref-1", providerPayoutId, new BigDecimal("75.00"), "PLN", "alice", AT
        )));
    }

    private ConsumerRecord<String, String> payoutFailed(String eventId, String providerPayoutId) {
        return record(PAYOUT_FAILED, objectMapper.writeValueAsString(new PayoutFailedEvent(
                eventId, 1, "ref-1", providerPayoutId, new BigDecimal("75.00"), "PLN", "alice",
                "PAYOUT_FAILED", null, AT
        )));
    }

    private ConsumerRecord<String, String> payoutReturned(String eventId, String amount) {
        return record(PAYOUT_RETURNED, objectMapper.writeValueAsString(new PayoutReturnedEvent(
                eventId, 1, "ref-1", null, new BigDecimal(amount), "PLN", "alice", "PLATFORM_FUNDS_SHORT", AT
        )));
    }

    private static EventEnvelope eventWithId(String eventId) {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadsJacksonWouldQuote")
    void anUnparseablePayloadIsRefusedWithoutQuotingIt(String shape, String json) {
        // Guards a user id in a log line (ADR 0027): Jackson's message quotes the input, and this exception's message
        // and cause reach the retry listener's log lines and the dead letter's headers.
        assertThatThrownBy(() -> listener.onPaymentEvent(record(COMPLETED, json)))
                .isInstanceOf(UnreadablePaymentEventException.class)
                .hasNoCause()
                .satisfies(refused -> assertThat(refused.getMessage())
                        .contains("at line 1, column")
                        .doesNotContain(QUOTED_USER_PREFIX));
    }

    static Stream<Arguments> payloadsJacksonWouldQuote() {
        return Stream.of(
                Arguments.of("a user id where the amount goes", "{\"amount\":\"" + QUOTED_USER + "\"}"),
                Arguments.of("an unquoted user id", "{\"userId\":" + QUOTED_USER + "}")
        );
    }

    @Test
    void anUnrecognisedTypeIsRefusedWithoutQuotingTheHeader() {
        // Guards a user id in a log line (ADR 0027): the header's value would reach the retry listener's log lines.
        assertThatThrownBy(() -> listener.onPaymentEvent(record(QUOTED_USER, "{}")))
                .isInstanceOf(UnreadablePaymentEventException.class)
                .satisfies(refused -> assertThat(refused.getMessage()).doesNotContain(QUOTED_USER_PREFIX));
    }

    @Test
    void aRecordWithATypeHeaderAndNoValueIsDeadLetteredAtOnce() {
        // Jackson throws IllegalArgumentException for a null value, which is not a JacksonException. Unless it
        // is refused as unreadable, the record is retried and dead-lettered as if a retry could have fixed it.
        assertThatThrownBy(() -> listener.onPaymentEvent(record(COMPLETED, null)))
                .isInstanceOf(UnreadablePaymentEventException.class);

        verifyNoInteractions(handler, outcomes);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {COMPLETED, FAILED, PAYOUT_PAID, PAYOUT_FAILED, PAYOUT_RETURNED})
    void aJsonNullPayloadIsDeadLetteredAtOnce(String eventType) {
        // The literal null parses to a null event. Without the check the listener fails with a
        // NullPointerException, which is retried and reaches the dead-letter topic without saying why.
        assertThatThrownBy(() -> listener.onPaymentEvent(record(eventType, "null")))
                .isInstanceOf(UnreadablePaymentEventException.class);

        verifyNoInteractions(handler, withdrawalHandler, outcomes);
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

    @ParameterizedTest(name = "{1} {0}")
    @CsvSource({
            "10.123,  USD",
            "0.00001, USD",
            "10.5,    JPY",
            "10.5,    jpy",
            "1E+15,   USD"
    })
    void anAmountOffItsCurrencysGridIsRefusedRatherThanCredited(String amount, String currency) {
        // Guards two failures. 10.123 USD or 10.5 JPY would be credited as units no deposit or transfer can
        // produce. 0.00001 would reach the ledger as 0.0000, fail balance_history_amount_positive, and be retried
        // and dead-lettered instead of recorded with its payload.
        listener.onPaymentEvent(completed("evt-1", amount, currency, "alice"));

        verify(outcomes).recordRejection(eventWithId("evt-1"), eq(RejectionReason.INVALID_AMOUNT), any());
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
        when(outcomes.classify("evt-1", "ref-1", TransactionType.DEPOSIT))
                .thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(completed("evt-1", "50.00", "USD", "alice"));

        verify(outcomes, never()).recordRejection(any(), any(), any());
    }

    @Test
    void aSecondEventForOneReferenceIsRecordedAsAContractViolation() {
        doThrow(new DataIntegrityViolationException("transaction_reference")).when(handler).credit(any());
        when(outcomes.classify("evt-2", "ref-1", TransactionType.DEPOSIT))
                .thenReturn(DuplicateVerdict.REFERENCE_ALREADY_CREDITED);

        listener.onPaymentEvent(completed("evt-2", "50.00", "USD", "alice"));

        verify(outcomes).recordRejection(eventWithId("evt-2"), eq(RejectionReason.DUPLICATE_REFERENCE), any());
    }

    @Test
    void aPaymentForAWalletThatDoesNotExistIsRecordedRatherThanDeadLettered() {
        // The payload is kept so the event can be republished under a fresh id once the wallet exists.
        // Dead-lettering it would put a refusal the wallet understood among the records it could not read or
        // settle, without the reason that says what to fix.
        doThrow(new UnknownWalletException("USD")).when(handler).credit(any());

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
        when(outcomes.classify("evt-1", "ref-1", null)).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(completed("evt-1", "-5.00", "USD", "alice"));

        verify(outcomes).classify("evt-1", "ref-1", null);
    }

    @Test
    void aRefusalThatFailsForAnotherReasonIsStillRaised() {
        DataIntegrityViolationException somethingElse = new DataIntegrityViolationException("value too long");
        doThrow(somethingElse).when(outcomes).recordRejection(any(), any(), any());
        when(outcomes.classify(any(), any(), any())).thenReturn(DuplicateVerdict.NOT_A_DUPLICATE);

        assertThatThrownBy(() -> listener.onPaymentEvent(completed("evt-1", "-5.00", "USD", "alice")))
                .isSameAs(somethingElse);
    }

    @Test
    void aViolationThatIsNeitherBarrierIsRaisedRatherThanAcknowledged() {
        // The most important test here. Treating an unrecognised violation as a duplicate would acknowledge
        // an event whose credit never happened, and Kafka would never redeliver it.
        DataIntegrityViolationException somethingElse = new DataIntegrityViolationException("value too long");
        doThrow(somethingElse).when(handler).credit(any());
        when(outcomes.classify(any(), any(), any())).thenReturn(DuplicateVerdict.NOT_A_DUPLICATE);

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
        when(outcomes.classify("evt-9", "ref-1", null)).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(failed("evt-9"));

        verify(outcomes).classify("evt-9", "ref-1", null);
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
        when(outcomes.classify("evt-9", "ref-1", null)).thenReturn(verdict);

        assertThatThrownBy(() -> listener.onPaymentEvent(failed("evt-9"))).isSameAs(violation);
    }

    @Test
    void eachPayoutEventReachesItsOwnStepAndNeverThePaymentHandler() {
        // Guards a payout event dispatched as a payment, which would credit a deposit, or to the wrong step, which
        // would credit a withdrawal that is only paid.
        ConsumerRecord<String, String> paid = payoutPaid("evt-1", "po_1");
        ConsumerRecord<String, String> failed = payoutFailed("evt-2", "po_1");

        listener.onPaymentEvent(paid);
        listener.onPaymentEvent(failed);
        listener.onPaymentEvent(payoutReturned("evt-3", "75.00"));

        verify(withdrawalHandler).recordPaid(argThat(event -> "evt-1".equals(event.eventId())), eq(paid.value()));
        verify(withdrawalHandler).recordFailed(argThat(event -> "evt-2".equals(event.eventId())), eq(failed.value()));
        verify(withdrawalHandler).creditReturn(argThat(event -> "evt-3".equals(event.eventId())));
        verifyNoInteractions(handler, outcomes);
    }

    @Test
    void aPaidEventWithoutItsPayoutIdIsRefusedAsAnInvalidEnvelope() {
        // Guards a Paid event that names no payout: once a payout is paid it exists, so its id missing means the
        // producer is broken, and the status must not move on it.
        listener.onPaymentEvent(payoutPaid("evt-1", null));

        verify(outcomes).recordRejection(eventWithId("evt-1"), eq(RejectionReason.INVALID_ENVELOPE), any());
        verifyNoInteractions(withdrawalHandler);
    }

    @Test
    void aFailureOrAReturnWithoutAPayoutIdIsSettled() {
        // Guards refusing the events of a payout that was never created, such as a return for a short reserve,
        // which would leave the withdrawal's money debited for good.
        listener.onPaymentEvent(payoutFailed("evt-1", null));
        listener.onPaymentEvent(payoutReturned("evt-2", "75.00"));

        verify(withdrawalHandler).recordFailed(any(), any());
        verify(withdrawalHandler).creditReturn(any());
        verify(outcomes, never()).recordRejection(any(), any(), any());
    }

    @Test
    void aReturnOffTheGridIsRefusedRatherThanCredited() {
        // Guards crediting an amount no withdrawal could have debited.
        listener.onPaymentEvent(payoutReturned("evt-1", "75.001"));

        verify(outcomes).recordRejection(eventWithId("evt-1"), eq(RejectionReason.INVALID_AMOUNT), any());
        verifyNoInteractions(withdrawalHandler);
    }

    static Stream<Arguments> placementRefusals() {
        return Stream.of(
                Arguments.of(
                        (Supplier<RuntimeException>) () -> new UnknownWalletException("PLN"),
                        RejectionReason.WALLET_NOT_FOUND
                ),
                Arguments.of(
                        (Supplier<RuntimeException>) UnknownWithdrawalException::new,
                        RejectionReason.WITHDRAWAL_NOT_FOUND
                ),
                Arguments.of(
                        (Supplier<RuntimeException>) WithdrawalMismatchException::new,
                        RejectionReason.WITHDRAWAL_MISMATCH
                )
        );
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("placementRefusals")
    void aPayoutEventTheWalletCannotPlaceIsRecordedWithItsReason(
            Supplier<RuntimeException> refusal,
            RejectionReason reason
    ) {
        // Guards dead-lettering a refusal the wallet understood: it is recorded with its payload, so an operator
        // sees why and can republish it under a fresh event id once the cause is fixed.
        doThrow(refusal.get()).when(withdrawalHandler).creditReturn(any());

        listener.onPaymentEvent(payoutReturned("evt-1", "75.00"));

        verify(outcomes).recordRejection(eventWithId("evt-1"), eq(reason), any());
    }

    @Test
    void aSecondReturnForOneReferenceIsRecordedAsADuplicateReference() {
        // Guards a return being taken for a stale status: a second credit under another event id is a producer
        // defect, judged by the WITHDRAWAL_RETURNED row and not by the withdrawal's debit under the same reference.
        doThrow(new DataIntegrityViolationException("transaction_reference"))
                .when(withdrawalHandler)
                .creditReturn(any());
        when(outcomes.classify("evt-2", "ref-1", TransactionType.WITHDRAWAL_RETURNED))
                .thenReturn(DuplicateVerdict.REFERENCE_ALREADY_CREDITED);

        listener.onPaymentEvent(payoutReturned("evt-2", "75.00"));

        verify(outcomes).recordRejection(eventWithId("evt-2"), eq(RejectionReason.DUPLICATE_REFERENCE), any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PAYOUT_PAID, PAYOUT_FAILED})
    void aRedeliveredStatusEventIsAcknowledgedOnItsEventIdAlone(String eventType) {
        // Guards dead-lettering the second copy of a Paid or Failed event, and judging it by any ledger row: it moves
        // no money, so only its event id can make it a duplicate.
        DataIntegrityViolationException redelivery = new DataIntegrityViolationException("event_id");
        doThrow(redelivery).when(withdrawalHandler).recordPaid(any(), any());
        doThrow(redelivery).when(withdrawalHandler).recordFailed(any(), any());
        when(outcomes.classify("evt-1", "ref-1", null)).thenReturn(DuplicateVerdict.EVENT_ALREADY_PROCESSED);

        listener.onPaymentEvent(
                PAYOUT_PAID.equals(eventType) ? payoutPaid("evt-1", "po_1") : payoutFailed("evt-1", "po_1")
        );

        verify(outcomes).classify("evt-1", "ref-1", null);
        verify(outcomes, never()).recordRejection(any(), any(), any());
    }

    @Test
    void aReturnViolationThatIsNeitherBarrierIsRaisedRatherThanAcknowledged() {
        // Guards acknowledging a return whose credit never happened: Kafka would never redeliver it, and the
        // withdrawal's money would stay debited.
        DataIntegrityViolationException somethingElse = new DataIntegrityViolationException("value too long");
        doThrow(somethingElse).when(withdrawalHandler).creditReturn(any());
        when(outcomes.classify(any(), any(), any())).thenReturn(DuplicateVerdict.NOT_A_DUPLICATE);

        assertThatThrownBy(() -> listener.onPaymentEvent(payoutReturned("evt-1", "75.00"))).isSameAs(somethingElse);

        verify(outcomes, never()).recordRejection(any(), any(), any());
    }
}
