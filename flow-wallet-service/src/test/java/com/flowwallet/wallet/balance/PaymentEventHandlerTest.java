package com.flowwallet.wallet.balance;

import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentEventHandlerTest {
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final BalanceHistoryRepository balanceHistory = mock(BalanceHistoryRepository.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final PaymentEventHandler handler =
            new PaymentEventHandler(wallets, balanceHistory, processedEvents);

    private PaymentCompletedEvent completed(String amount) {
        return new PaymentCompletedEvent("evt-1", 1, "ref-1", "pi_1",
                new BigDecimal(amount), "USD", "alice", Instant.parse("2026-09-05T12:00:00Z"));
    }

    @Test
    void refusesWhenTheUserHasNoWalletInThatCurrency() {
        // A wallet is never a side effect of a payment. Deposits go through the wallet, which refuses with
        // 404 before any money moves, so reaching here means a payment got in by some other route.
        when(wallets.lockByUserIdAndCurrency("alice", "USD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.credit(completed("50.00")))
                .isInstanceOf(UnknownWalletException.class);

        verify(wallets, never()).save(any());
        verifyNoInteractions(balanceHistory);
    }

    @Test
    void theRefusalIsThrownInsideTheTransactionSoTheBarrierRowRollsBackWithIt() {
        // If the barrier row survived a refused credit, recording the refusal afterwards would violate the
        // unique event id and dead-letter an event the wallet understood perfectly well.
        when(wallets.lockByUserIdAndCurrency(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.credit(completed("50.00")))
                .isInstanceOf(UnknownWalletException.class);

        // The row was written before the lookup, so only the rollback can undo it -- which requires the
        // throw to happen inside the same transactional method.
        verify(processedEvents).saveAndFlush(any());
    }

    @Test
    void creditsTheExistingWalletAndRecordsBothSidesOfTheMovement() {
        Wallet existing = Wallet.open("alice", "USD");
        existing.credit(new BigDecimal("20.00"));
        when(wallets.lockByUserIdAndCurrency("alice", "USD")).thenReturn(Optional.of(existing));

        handler.credit(completed("30.00"));

        assertThat(existing.getBalance()).isEqualByComparingTo("50.00");
        ArgumentCaptor<BalanceHistory> movement = ArgumentCaptor.forClass(BalanceHistory.class);
        verify(balanceHistory).saveAndFlush(movement.capture());
        assertThat(movement.getValue().getBalanceBefore()).isEqualByComparingTo("20.00");
        assertThat(movement.getValue().getBalanceAfter()).isEqualByComparingTo("50.00");
        assertThat(movement.getValue().getTransactionReference()).isEqualTo("ref-1");
        assertThat(movement.getValue().getEventId()).isEqualTo("evt-1");
        verify(wallets, never()).save(any());
    }

    @Test
    void theBarrierRowIsWrittenBeforeTheWalletIsEvenLoaded() {
        // Ordering matters: the event_id barrier must decide before any money is touched, so a redelivery
        // cannot get as far as reading a wallet.
        when(wallets.lockByUserIdAndCurrency(any(), any())).thenReturn(Optional.of(Wallet.open("alice", "USD")));

        handler.credit(completed("50.00"));

        var order = inOrder(processedEvents, wallets);
        order.verify(processedEvents).saveAndFlush(any());
        order.verify(wallets).lockByUserIdAndCurrency("alice", "USD");
    }

    @Test
    void aFailedPaymentTouchesNoWalletAndNoLedger() {
        // This is what makes the order of a failure and a success for one reference irrelevant: only one of
        // the two outcomes moves money, so neither can undo the other.
        PaymentFailedEvent failed = new PaymentFailedEvent("evt-9", 1, "ref-9", "pi_9",
                new BigDecimal("10.00"), "USD", "alice", "card_declined",
                Instant.parse("2026-09-05T12:05:00Z"));

        handler.recordFailure(failed, "{}");

        verify(processedEvents).saveAndFlush(any());
        verifyNoInteractions(wallets, balanceHistory);
    }
}
