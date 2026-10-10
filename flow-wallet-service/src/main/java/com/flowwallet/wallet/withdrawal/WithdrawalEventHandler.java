package com.flowwallet.wallet.withdrawal;

import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.EventEnvelope;
import com.flowwallet.wallet.balance.ProcessedEvent;
import com.flowwallet.wallet.balance.ProcessedEventRepository;
import com.flowwallet.wallet.balance.UnknownWalletException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Applies a payout event to its withdrawal. The barrier row, the status and, for a return, the credit and its ledger
 * row commit together or not at all.
 * <p>
 * Each method writes the {@code processed_events} row first, then locks the wallet, then reads the withdrawal. The
 * isolation stays READ COMMITTED, because the read comes after a lock that may have waited and must see what the
 * holder committed. A refusal is thrown inside the transaction, so the barrier row rolls back with it, and
 * {@code PaymentEventListener} records it afterwards. Nothing is caught here.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md and
 * docs/adr/0011-wallet-row-locking.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalEventHandler {
    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;
    private final BalanceHistoryRepository balanceHistory;
    private final ProcessedEventRepository processedEvents;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordPaid(PayoutPaidEvent event, String payload) {
        EventEnvelope envelope = EventEnvelope.of(event);
        processedEvents.saveAndFlush(ProcessedEvent.statusRecorded(envelope, payload));

        Withdrawal withdrawal = lock(envelope).withdrawal();
        logStatus(envelope, withdrawal, withdrawal.markPaid());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordFailed(PayoutFailedEvent event, String payload) {
        EventEnvelope envelope = EventEnvelope.of(event);
        processedEvents.saveAndFlush(ProcessedEvent.statusRecorded(envelope, payload));

        Withdrawal withdrawal = lock(envelope).withdrawal();
        logStatus(envelope, withdrawal, withdrawal.markReturnPending(event.reasonCode()));
    }

    /**
     * Credits the withdrawal's amount back once under its reference. The credit does not depend on the status: a
     * second return under another event id reaches the {@code WITHDRAWAL_RETURNED} ledger key and is refused there,
     * as a second credit for one deposit is.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void creditReturn(PayoutReturnedEvent event) {
        EventEnvelope envelope = EventEnvelope.of(event);
        processedEvents.saveAndFlush(ProcessedEvent.returnCredited(envelope));

        Locked locked = lock(envelope);
        locked.withdrawal().markReturned(event.reasonCode());

        BigDecimal amount = locked.withdrawal().getAmount();
        BigDecimal balanceBefore = locked.wallet().credit(amount);
        balanceHistory.saveAndFlush(BalanceHistory.withdrawalReturned(
                locked.wallet(),
                envelope.transactionReference(),
                envelope.eventId(),
                amount,
                balanceBefore
        ));

        log.info(
                "Returned {} {} to wallet {} for withdrawal {}",
                amount,
                envelope.currency(),
                locked.wallet().getId(),
                envelope.transactionReference()
        );
    }

    /**
     * Locks the wallet the event names and reads the withdrawal under its reference, refusing one that is missing,
     * refused, or for another wallet or amount.
     */
    private Locked lock(EventEnvelope envelope) {
        Wallet wallet = wallets.lockByUserIdAndCurrency(envelope.userId(), envelope.currency())
                .orElseThrow(() -> new UnknownWalletException(envelope.currency()));

        Withdrawal withdrawal = withdrawals.findByTransactionReference(envelope.transactionReference())
                .filter(found -> found.getStatus() != WithdrawalStatus.REFUSED)
                .orElseThrow(WithdrawalNotFoundException::new);
        if (!withdrawal.hasTerms(wallet.getId(), envelope.amount())) {
            throw new WithdrawalMismatchException();
        }
        return new Locked(wallet, withdrawal);
    }

    private void logStatus(EventEnvelope envelope, Withdrawal withdrawal, boolean raised) {
        if (raised) {
            log.info("Withdrawal {} is {}", envelope.transactionReference(), withdrawal.getStatus());
        } else {
            log.info(
                    "Withdrawal {} stays {}; event {} recorded without a change",
                    envelope.transactionReference(),
                    withdrawal.getStatus(),
                    envelope.eventId()
            );
        }
    }

    private record Locked(Wallet wallet, Withdrawal withdrawal) {
    }
}
