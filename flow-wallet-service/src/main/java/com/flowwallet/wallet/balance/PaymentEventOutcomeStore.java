package com.flowwallet.wallet.balance;

import com.flowwallet.wallet.enums.RejectionReason;
import com.flowwallet.wallet.enums.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
     * The reference check names the one ledger type the write could have hit: {@code DEPOSIT} for a credit,
     * {@code WITHDRAWAL_RETURNED} for a return, and none for an event that moves no money, which only the event id
     * can make a duplicate. A check for any row under the reference would take a transfer leg or a withdrawal's
     * debit for a credit and acknowledge, as a duplicate, money that never arrived.
     * See docs/adr/0010-idempotent-payment-event-consumer.md and
     * docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
     *
     * @param barrierType the ledger type the write would have created, or null when it creates no ledger row
     */
    @Transactional(readOnly = true)
    public DuplicateVerdict classify(
            String eventId,
            String transactionReference,
            @Nullable TransactionType barrierType
    ) {
        if (processedEvents.findByEventId(eventId).isPresent()) {
            return DuplicateVerdict.EVENT_ALREADY_PROCESSED;
        }
        if (barrierType != null
                && balanceHistory.findByTransactionReferenceAndType(transactionReference, barrierType).isPresent()) {
            return DuplicateVerdict.REFERENCE_ALREADY_CREDITED;
        }
        return DuplicateVerdict.NOT_A_DUPLICATE;
    }

    /**
     * Records a refusal as a {@code REJECTED} row with the whole payload, so the event can be republished under a
     * fresh event id once the cause is fixed. The row takes the original id, which a replay would otherwise meet.
     */
    @Transactional
    public void recordRejection(EventEnvelope event, RejectionReason reason, String payload) {
        processedEvents.saveAndFlush(ProcessedEvent.rejected(event, reason, payload));
    }
}
