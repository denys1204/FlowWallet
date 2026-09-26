package com.flowwallet.wallet.balance;

import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The money transaction: the barrier row, the balance and the ledger entry commit together or not at all.
 * <p>
 * Nothing is caught here. A constraint violation aborts the Postgres transaction, so
 * {@link PaymentEventListener} classifies it after the rollback.
 * See docs/adr/0010-idempotent-payment-event-consumer.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventHandler {
    private final WalletRepository wallets;
    private final BalanceHistoryRepository balanceHistory;
    private final ProcessedEventRepository processedEvents;

    /**
     * Credits a confirmed payment exactly once. The wallet is locked for the rest of the transaction and never
     * created here.
     * <p>
     * {@link UnknownWalletException} is thrown inside the transaction on purpose: the barrier row flushed first
     * must roll back with it, or recording the refusal afterwards would hit the unique event id.
     * See docs/adr/0010-idempotent-payment-event-consumer.md and docs/adr/0011-wallet-row-locking.md.
     */
    @Transactional
    public void credit(PaymentCompletedEvent event) {
        processedEvents.saveAndFlush(ProcessedEvent.credited(event));

        Wallet wallet = wallets.lockByUserIdAndCurrency(event.userId(), event.currency())
                .orElseThrow(() -> new UnknownWalletException(event.userId(), event.currency()));

        BigDecimal balanceBefore = wallet.credit(event.amount());
        balanceHistory.saveAndFlush(BalanceHistory.deposit(
                wallet, event.transactionReference(), event.eventId(), event.amount(), balanceBefore
        ));

        log.info("Credited {} {} to wallet {} for transaction {}",
                event.amount(), event.currency(), wallet.getId(), event.transactionReference());
    }

    /**
     * Records a failed payment. It moves no money, which is why the consumer needs no ordering between a failure
     * and a success for one reference. See docs/adr/0009-payment-event-contract.md.
     */
    @Transactional
    public void recordFailure(PaymentFailedEvent event, String payload) {
        processedEvents.saveAndFlush(ProcessedEvent.failureRecorded(event, payload));

        log.info("Recorded failed payment {}: {}", event.transactionReference(), event.reason());
    }
}
