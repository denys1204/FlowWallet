package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WithdrawalCommandListenerTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final String OTHER_USER = "4c8d2e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f";
    private static final String REQUESTED = KafkaConstants.EVENT_TYPE_WITHDRAWAL_REQUESTED;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayoutAcceptance acceptance = mock(PayoutAcceptance.class);
    private final WithdrawalCommandListener listener = new WithdrawalCommandListener(objectMapper, acceptance);

    private ConsumerRecord<String, String> record(String eventType, String json) {
        var record = new ConsumerRecord<>(KafkaConstants.WALLET_WITHDRAWALS_TOPIC, 0, 0L, REFERENCE, json);
        if (eventType != null) {
            record.headers().add(KafkaConstants.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private ConsumerRecord<String, String> command(String reference, String userId, String amount, String currency) {
        BigDecimal value = amount == null ? null : new BigDecimal(amount);
        var event = new WithdrawalRequestedEvent("evt-1", 1, reference, userId, value, currency);
        return record(REQUESTED, objectMapper.writeValueAsString(event));
    }

    private ConsumerRecord<String, String> command() {
        return command(REFERENCE, USER, "75.00", "PLN");
    }

    @Test
    void aWellFormedCommandIsAccepted() {
        // Guards a command that never becomes a payout, which leaves its debited money in the wallet for good.
        listener.onWithdrawalCommand(command());

        verify(acceptance).accept(argThat(accepted -> REFERENCE.equals(accepted.transactionReference())));
    }

    @ParameterizedTest(name = "eventType {0}")
    @ValueSource(strings = {"PaymentCompletedEvent", "WithdrawalCancelledEvent"})
    void aRecordOfAnotherTypeIsUnreadable(String eventType) {
        // Guards a record the consumer would read by its shape instead of its header.
        assertThatThrownBy(() -> listener.onWithdrawalCommand(record(eventType, "{}")))
                .isInstanceOf(UnreadableWithdrawalCommandException.class);

        verifyNoInteractions(acceptance);
    }

    @Test
    void aRecordWithoutATypeHeaderOrAValueOrJsonIsUnreadable() {
        // Guards a record retried and dead-lettered under a misleading exception instead of at once.
        assertThatThrownBy(() -> listener.onWithdrawalCommand(record(null, "{}")))
                .isInstanceOf(UnreadableWithdrawalCommandException.class);
        assertThatThrownBy(() -> listener.onWithdrawalCommand(record(REQUESTED, null)))
                .isInstanceOf(UnreadableWithdrawalCommandException.class);
        assertThatThrownBy(() -> listener.onWithdrawalCommand(record(REQUESTED, "{not json")))
                .isInstanceOf(UnreadableWithdrawalCommandException.class);
        assertThatThrownBy(() -> listener.onWithdrawalCommand(record(REQUESTED, "null")))
                .isInstanceOf(UnreadableWithdrawalCommandException.class);

        verifyNoInteractions(acceptance);
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}")
    @CsvSource(nullValues = "NULL", value = {
            "NOT-A-UUID, USER,                                 75.00,                PLN",
            "NULL,       USER,                                 75.00,                PLN",
            "REF,        NULL,                                 75.00,                PLN",
            "REF,        alice,                                75.00,                PLN",
            "REF,        0B9E5C1A-7D2F-4A3B-8C4D-5E6F7A8B9C0D, 75.00,                PLN",
            "REF,        USER,                                 NULL,                 PLN",
            "REF,        USER,                                 0,                    PLN",
            "REF,        USER,                                 -5.00,                PLN",
            "REF,        USER,                                 75.001,               PLN",
            "REF,        USER,                                 1E+16,                PLN",
            "REF,        USER,                                 1E+2147483647,        PLN",
            "REF,        USER,                                 12345678E+2147483644, PLN",
            "REF,        USER,                                 75.00,                NULL",
            "REF,        USER,                                 75.00,                PL",
    })
    void aCommandWithAnInvalidShapeIsDeadLetteredAtOnce(
            String reference,
            String userId,
            String amount,
            String currency
    ) {
        // Guards a producer defect retried as if it could pass, and a payout created from a command it describes
        // wrongly, such as an amount finer than its currency's grid, one whose exponent wraps an int digit count, or
        // a user id the column cannot hold.
        ConsumerRecord<String, String> malformed = command(
                "REF".equals(reference) ? REFERENCE : reference,
                "USER".equals(userId) ? USER : userId,
                amount,
                currency
        );

        assertThatThrownBy(() -> listener.onWithdrawalCommand(malformed))
                .isInstanceOf(UnreadableWithdrawalCommandException.class);

        verifyNoInteractions(acceptance);
    }

    @Test
    void aRedeliveryWithTheSameTermsIsAcknowledged() {
        // Guards dead-lettering an ordinary at-least-once redelivery, which would leave an operator to clear it.
        doThrow(new DataIntegrityViolationException("payouts_reference_key")).when(acceptance).accept(any());
        when(acceptance.findByReference(REFERENCE)).thenReturn(Optional.of(accepted(USER, "75.0000", "PLN")));

        assertThatCode(() -> listener.onWithdrawalCommand(command(REFERENCE, USER, "75.00", "pln")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
            "OTHER_USER, 75.0000, PLN",
            "USER,       75.0100, PLN",
            "USER,       75.0000, EUR",
    })
    void aCommandWithOtherTermsUnderAnAcceptedReferenceIsAConflict(String user, String amount, String currency) {
        // Guards acknowledging a command that describes another withdrawal than the one accepted, which would pay
        // out the first terms against a debit of the second.
        doThrow(new DataIntegrityViolationException("payouts_reference_key")).when(acceptance).accept(any());
        String userId = "USER".equals(user) ? USER : OTHER_USER;
        when(acceptance.findByReference(REFERENCE)).thenReturn(Optional.of(accepted(userId, amount, currency)));

        assertThatThrownBy(() -> listener.onWithdrawalCommand(command()))
                .isInstanceOf(ConflictingWithdrawalCommandException.class);
    }

    @Test
    void aViolationWithNoRowUnderTheReferenceIsRetried() {
        // Guards acknowledging a command whose payout was never inserted: another constraint refused it, and a retry
        // may succeed where an acknowledgement would lose the withdrawal.
        DataIntegrityViolationException violation = new DataIntegrityViolationException("payouts_amount_positive");
        doThrow(violation).when(acceptance).accept(any());
        when(acceptance.findByReference(REFERENCE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onWithdrawalCommand(command())).isSameAs(violation);
    }

    private static Payout accepted(String userId, String amount, String currency) {
        return Payout.accepted(
                REFERENCE,
                userId,
                new BigDecimal(amount),
                currency,
                Instant.parse("2026-10-10T12:00:00Z")
        );
    }
}
