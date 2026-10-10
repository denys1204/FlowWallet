package com.flowwallet.wallet.withdrawal;

import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.ProcessedEvent;
import com.flowwallet.wallet.balance.ProcessedEventRepository;
import com.flowwallet.wallet.balance.UnknownWalletException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.enums.ProcessedEventOutcome;
import com.flowwallet.wallet.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WithdrawalEventHandlerTest {
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final Instant AT = Instant.parse("2026-10-10T12:00:00Z");

    private final WalletRepository wallets = mock(WalletRepository.class);
    private final WithdrawalRepository withdrawals = mock(WithdrawalRepository.class);
    private final BalanceHistoryRepository balanceHistory = mock(BalanceHistoryRepository.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final WithdrawalEventHandler handler =
            new WithdrawalEventHandler(wallets, withdrawals, balanceHistory, processedEvents);

    private final Wallet wallet = Wallet.builder()
            .id(11L)
            .userId("alice")
            .currency("PLN")
            .balance(new BigDecimal("25.0000"))
            .lastEntryNo(5L)
            .build();

    private final Withdrawal withdrawal = Withdrawal.pending(wallet, REFERENCE, new BigDecimal("75.0000"));

    private PayoutPaidEvent paid(String amount) {
        return new PayoutPaidEvent("evt-1", 1, REFERENCE, "po_1", new BigDecimal(amount), "PLN", "alice", AT);
    }

    private PayoutFailedEvent failed() {
        return new PayoutFailedEvent(
                "evt-2", 1, REFERENCE, "po_1", new BigDecimal("75.00"), "PLN", "alice",
                "PAYOUT_FAILED", "no_account", AT
        );
    }

    private PayoutReturnedEvent returned(String eventId) {
        return new PayoutReturnedEvent(
                eventId, 1, REFERENCE, "po_1", new BigDecimal("75.00"), "PLN", "alice", "PAYOUT_FAILED", AT
        );
    }

    private void givenTheWalletAndItsWithdrawal() {
        when(wallets.lockByUserIdAndCurrency("alice", "PLN")).thenReturn(Optional.of(wallet));
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.of(withdrawal));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"paid", "failed", "returned"})
    void theBarrierRowComesFirstThenTheLockThenTheWithdrawal(String step) {
        // Guards the order the barrier and the lock rules depend on: a redelivery must stop at the event id before
        // any wallet is touched, and the withdrawal read must come after the lock, never before it.
        givenTheWalletAndItsWithdrawal();

        switch (step) {
            case "paid" -> handler.recordPaid(paid("75.00"), "{}");
            case "failed" -> handler.recordFailed(failed(), "{}");
            default -> handler.creditReturn(returned("evt-3"));
        }

        var order = inOrder(processedEvents, wallets, withdrawals);
        order.verify(processedEvents).saveAndFlush(any());
        order.verify(wallets).lockByUserIdAndCurrency("alice", "PLN");
        order.verify(withdrawals).findByTransactionReference(REFERENCE);
    }

    @Test
    void aPaidEventRaisesTheStatusRecordsTheEventAndMovesNoMoney() {
        // Guards a Paid event touching the balance or the ledger, which only a return may do.
        givenTheWalletAndItsWithdrawal();

        handler.recordPaid(paid("75.00"), "{\"eventId\":\"evt-1\"}");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PAID);
        assertThat(wallet.getBalance()).isEqualByComparingTo("25.0000");
        assertThat(savedRow().getOutcome()).isEqualTo(ProcessedEventOutcome.STATUS_RECORDED);
        verifyNoInteractions(balanceHistory);
    }

    @Test
    void aStaleStatusEventIsRecordedWithoutUndoingALaterStatus() {
        // Guards a late Paid moving a withdrawal on its way back to PAID, and a stale event left unrecorded, which
        // a redelivery would then apply again.
        givenTheWalletAndItsWithdrawal();
        withdrawal.markReturnPending("PAYOUT_FAILED");

        handler.recordPaid(paid("75.00"), "{}");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.RETURN_PENDING);
        assertThat(savedRow().getOutcome()).isEqualTo(ProcessedEventOutcome.STATUS_RECORDED);
    }

    @Test
    void aFailedEventShowsTheMoneyOnItsWayBackAndMovesNone() {
        // Guards crediting on a failure, before the reversal brought the money back to the platform.
        givenTheWalletAndItsWithdrawal();

        handler.recordFailed(failed(), "{}");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.RETURN_PENDING);
        assertThat(withdrawal.getReturnReason()).isEqualTo("PAYOUT_FAILED");
        assertThat(wallet.getBalance()).isEqualByComparingTo("25.0000");
        verifyNoInteractions(balanceHistory);
    }

    @Test
    void aReturnCreditsTheDebitedAmountUnderTheReferenceAndTheEvent() {
        // Guards the one payout event that moves money: the amount the withdrawal debited, a ledger row the
        // WITHDRAWAL_RETURNED key can refuse a second time, numbered from the locked wallet's counter.
        givenTheWalletAndItsWithdrawal();

        handler.creditReturn(returned("evt-3"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("100.0000");
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.RETURNED);
        assertThat(savedRow().getOutcome()).isEqualTo(ProcessedEventOutcome.RETURN_CREDITED);

        ArgumentCaptor<BalanceHistory> movement = ArgumentCaptor.forClass(BalanceHistory.class);
        verify(balanceHistory).saveAndFlush(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(TransactionType.WITHDRAWAL_RETURNED);
        assertThat(movement.getValue().getTransactionReference()).isEqualTo(REFERENCE);
        assertThat(movement.getValue().getEventId()).isEqualTo("evt-3");
        assertThat(movement.getValue().getAmount()).isEqualTo(new BigDecimal("75.0000"));
        assertThat(movement.getValue().getBalanceAfter()).isEqualByComparingTo("100.0000");
        assertThat(movement.getValue().getEntryNo()).isEqualTo(6L);
    }

    @Test
    void aSecondReturnStillReachesTheLedgerKey() {
        // Guards gating the credit on the status: a second return under another event id must meet the
        // WITHDRAWAL_RETURNED key and be refused as DUPLICATE_REFERENCE, not be acknowledged as a stale status.
        givenTheWalletAndItsWithdrawal();
        withdrawal.markReturned("PAYOUT_FAILED");

        handler.creditReturn(returned("evt-4"));

        verify(balanceHistory).saveAndFlush(any());
    }

    @Test
    void anEventForAWalletTheUserDoesNotHoldIsRefusedInsideTheTransaction() {
        // Guards the refusal escaping after the commit: the barrier row written first must roll back with it, or
        // recording the refusal afterwards would meet the unique event id.
        when(wallets.lockByUserIdAndCurrency("alice", "PLN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.creditReturn(returned("evt-3"))).isInstanceOf(UnknownWalletException.class);

        verify(processedEvents).saveAndFlush(any());
        verifyNoInteractions(withdrawals, balanceHistory);
    }

    @Test
    void anEventWithNoWithdrawalOrARefusedOneIsNotFound() {
        // Guards crediting money that never left: no withdrawal, or one the wallet refused, debited nothing.
        when(wallets.lockByUserIdAndCurrency("alice", "PLN")).thenReturn(Optional.of(wallet));
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.creditReturn(returned("evt-3")))
                .isInstanceOf(UnknownWithdrawalException.class);

        Withdrawal refused = Withdrawal.refused(
                wallet,
                REFERENCE,
                new BigDecimal("75.00"),
                WithdrawalRefusalReason.INSUFFICIENT_FUNDS
        );
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.of(refused));

        assertThatThrownBy(() -> handler.creditReturn(returned("evt-3")))
                .isInstanceOf(UnknownWithdrawalException.class);
        verify(balanceHistory, never()).saveAndFlush(any());
        assertThat(wallet.getBalance()).isEqualByComparingTo("25.0000");
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = {"75.01", "74.99"})
    void anEventWithAnotherAmountIsAMismatch(String amount) {
        // Guards a producer defect moving the status of, or crediting, a withdrawal it does not describe.
        givenTheWalletAndItsWithdrawal();

        assertThatThrownBy(() -> handler.recordPaid(paid(amount), "{}"))
                .isInstanceOf(WithdrawalMismatchException.class);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING);
    }

    @Test
    void anEventForAnotherWalletsWithdrawalIsAMismatch() {
        // Guards a return for one user's withdrawal crediting another user's wallet in the same currency.
        Withdrawal someoneElses = Withdrawal.builder()
                .walletId(12L)
                .transactionReference(REFERENCE)
                .amount(new BigDecimal("75.0000"))
                .status(WithdrawalStatus.PENDING)
                .build();
        when(wallets.lockByUserIdAndCurrency("alice", "PLN")).thenReturn(Optional.of(wallet));
        when(withdrawals.findByTransactionReference(REFERENCE)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> handler.creditReturn(returned("evt-3")))
                .isInstanceOf(WithdrawalMismatchException.class);

        verify(balanceHistory, never()).saveAndFlush(any());
    }

    @Test
    void everyStepIsPinnedToReadCommitted() {
        // Guards the isolation being removed or changed. Under REPEATABLE READ the withdrawal read after a lock that
        // waited would not see what the lock's holder committed, and the lock itself would fail after any wait.
        Method[] steps = Arrays.stream(WithdrawalEventHandler.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toArray(Method[]::new);

        assertThat(steps).extracting(Method::getName).contains("recordPaid", "recordFailed", "creditReturn");
        assertThat(steps).allSatisfy(step -> {
            Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(step, Transactional.class);
            assertThat(transactional).isNotNull();
            assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        });
    }

    private ProcessedEvent savedRow() {
        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).saveAndFlush(saved.capture());
        return saved.getValue();
    }
}
