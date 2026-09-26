package com.flowwallet.wallet.balance;

import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentEventOutcomeStoreTest {
    private final BalanceHistoryRepository balanceHistory = mock(BalanceHistoryRepository.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final PaymentEventOutcomeStore outcomes =
            new PaymentEventOutcomeStore(balanceHistory, processedEvents);

    private PaymentCompletedEvent completed(String eventId) {
        return new PaymentCompletedEvent(
                eventId, 1,
                "ref-1",
                "pi_1",
                new BigDecimal("30.00"), "USD", "alice", Instant.parse("2026-09-05T12:00:00Z")
        );
    }

    @Test
    void aReferenceThatAlsoCarriesTransferLegsIsJudgedByItsDepositRowAlone() {
        // Since migration 005 one reference can own a movement of each type. A lookup expecting a single row
        // for the reference would throw once there are two, dead-lettering an event that should have been
        // classified; a check for any row would read a transfer leg as a credit and acknowledge a payment that
        // was never credited. Here every type except DEPOSIT has a row, so only the typed lookup reaches
        // NOT_A_DUPLICATE.
        Wallet sender = Wallet.builder()
                .id(8L)
                .userId("bob")
                .currency("USD")
                .balance(new BigDecimal("30.0000"))
                .build();

        BalanceHistory transferLeg = BalanceHistory.transferOut(
                sender, "ref-1", "alice", new BigDecimal("30.0000"), new BigDecimal("30.0000")
        );

        when(balanceHistory.findByTransactionReferenceAndType(eq("ref-1"), any())).thenAnswer(call ->
                call.getArgument(1) == TransactionType.DEPOSIT ? Optional.empty() : Optional.of(transferLeg));

        assertThat(outcomes.classify("evt-1", "ref-1")).isEqualTo(DuplicateVerdict.NOT_A_DUPLICATE);

        verify(balanceHistory).findByTransactionReferenceAndType("ref-1", TransactionType.DEPOSIT);
        verifyNoMoreInteractions(balanceHistory);
    }

    @Test
    void aDepositUnderTheReferenceIsStillReportedAsAlreadyCredited() {
        // The typed check must keep the one case it exists for: a second, different event for a payment that
        // was already credited is a producer contract violation and is recorded as one. The event id must
        // still be asked first, or a plain redelivery of the event that made the credit would be logged as
        // that violation.
        BalanceHistory deposit = BalanceHistory.deposit(
                Wallet.open("alice", "USD"), "ref-1", "evt-1", new BigDecimal("30.00"), BigDecimal.ZERO
        );
        when(balanceHistory.findByTransactionReferenceAndType("ref-1", TransactionType.DEPOSIT))
                .thenReturn(Optional.of(deposit));
        when(processedEvents.findByEventId("evt-1"))
                .thenReturn(Optional.of(ProcessedEvent.credited(completed("evt-1"))));

        assertThat(outcomes.classify("evt-2", "ref-1")).isEqualTo(DuplicateVerdict.REFERENCE_ALREADY_CREDITED);
        assertThat(outcomes.classify("evt-1", "ref-1")).isEqualTo(DuplicateVerdict.EVENT_ALREADY_PROCESSED);
    }
}
