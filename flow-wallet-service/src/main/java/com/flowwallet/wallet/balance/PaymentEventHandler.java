package com.flowwallet.wallet.balance;

import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The money transaction: everything a credit touches commits together or not at all.
 * <p>
 * One transaction spans the barrier row, the wallet update and the ledger entry, which is what makes the
 * balance and its history unable to disagree. There is no intermediate state, so there is nothing to
 * reconcile later and no sweeper to write.
 * <p>
 * Nothing is caught here. A constraint violation aborts the transaction in Postgres, so recovering in place
 * would run the recovery against a connection that refuses every further statement; the caller classifies
 * it afterwards, from a fresh transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventHandler {
    private final WalletRepository wallets;
    private final BalanceHistoryRepository balanceHistory;
    private final ProcessedEventRepository processedEvents;

    /**
     * Credits a confirmed payment exactly once.
     * <p>
     * The wallet is resolved by the owner and currency the event carries, and opened if the user has none
     * in that currency yet — which is the ordinary case for a first deposit, since nothing else creates
     * wallets before one is needed. Two events racing to open the same wallet is not handled here: the
     * unique constraint on (user_id, currency) decides it, and the loser is redelivered into a fresh
     * transaction that finds the winner's row.
     * <p>
     * Once the wallet exists the race stops being one, because the lookup takes a row lock and holds it for
     * the rest of the transaction. Letting the version decide instead would fail the loser, and a losing
     * credit costs a rolled-back transaction and a redelivery for something the lock settles in microseconds.
     */
    @Transactional
    public void credit(PaymentCompletedEvent event) {
        processedEvents.saveAndFlush(ProcessedEvent.credited(event));

        Wallet wallet = wallets.lockByUserIdAndCurrency(event.userId(), event.currency())
                .orElseGet(() -> wallets.save(Wallet.open(event.userId(), event.currency())));

        BigDecimal balanceBefore = wallet.credit(event.amount());
        balanceHistory.saveAndFlush(BalanceHistory.deposit(
                wallet, event.transactionReference(), event.eventId(), event.amount(), balanceBefore
        ));

        log.info("Credited {} {} to wallet {} for transaction {}",
                event.amount(), event.currency(), wallet.getId(), event.transactionReference());
    }

    /**
     * Records a failed payment. No balance moves, and that is the whole reason a failure and a success for
     * one transaction reference can arrive in either order without any ordering logic: neither outcome can
     * undo the other because only one of them moves money.
     */
    @Transactional
    public void recordFailure(PaymentFailedEvent event, String payload) {
        processedEvents.saveAndFlush(ProcessedEvent.failureRecorded(event, payload));

        log.info("Recorded failed payment {}: {}", event.transactionReference(), event.reason());
    }
}
