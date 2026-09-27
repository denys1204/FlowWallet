package com.flowwallet.wallet.balance;

import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.wallet.enums.RejectionReason;
import com.flowwallet.wallet.enums.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes that must happen in their own transaction, after the money transaction has rolled back. A
 * separate bean from {@link PaymentEventHandler}, because a self-invocation would bypass the transaction proxy.
 * See docs/adr/0006-short-transactions-across-bean-boundaries.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventOutcomeStore {
    private final BalanceHistoryRepository balanceHistory;
    private final ProcessedEventRepository processedEvents;

    /**
     * Asks the database which barrier refused the write, instead of parsing the exception.
     * <p>
     * The reference check names {@code DEPOSIT}, the one ledger barrier a credit can hit. A check for any row
     * under the reference would take a transfer leg for a credit and acknowledge, as a duplicate, a payment that
     * was never credited. A new barrier on the credit path needs its own verdict here.
     * See docs/adr/0010-idempotent-payment-event-consumer.md.
     */
    @Transactional(readOnly = true)
    public DuplicateVerdict classify(String eventId, String transactionReference) {
        if (processedEvents.findByEventId(eventId).isPresent()) {
            return DuplicateVerdict.EVENT_ALREADY_PROCESSED;
        }
        if (balanceHistory.findByTransactionReferenceAndType(transactionReference, TransactionType.DEPOSIT)
                .isPresent()) {
            return DuplicateVerdict.REFERENCE_ALREADY_CREDITED;
        }
        return DuplicateVerdict.NOT_A_DUPLICATE;
    }

    /**
     * Records a refusal as a {@code REJECTED} row with the whole payload, so the event can be replayed once the
     * cause is fixed.
     */
    @Transactional
    public void recordRejection(PaymentCompletedEvent event, RejectionReason reason, String payload) {
        processedEvents.saveAndFlush(ProcessedEvent.rejected(event, reason, payload));
    }
}
