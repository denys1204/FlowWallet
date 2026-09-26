package com.flowwallet.wallet.balance;

import com.flowwallet.wallet.enums.RejectionReason;
import com.flowwallet.wallet.enums.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Reads and writes that must happen in their own transaction, after the money transaction has rolled back.
 * <p>
 * A separate bean rather than more methods on {@link PaymentEventHandler}, because a self-invocation would
 * bypass the transaction proxy and run these on the aborted transaction they exist to escape.
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
     * Reading back beats inspecting constraint names: it needs no knowledge of the schema's naming, keeps
     * working when a constraint is renamed, and gives a definite answer for the case that matters most —
     * neither barrier — where guessing would ack a payment that was never credited.
     * <p>
     * The reference check asks about the {@code DEPOSIT} movement only, because that is the one ledger barrier
     * a credit can hit. Since migration 005 a reference may own one movement of each type, which is what lets
     * the two legs of a transfer share one, and an untyped check goes wrong whichever way it is written. A
     * lookup that expects one row throws once a reference owns two, so an event that should be classified is
     * retried and dead-lettered instead. A check for any row reads a movement of another type as a credit, and
     * after an unrelated violation it would acknowledge, as a duplicate, a payment that was never credited.
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
     * Records that an event was refused, so the refusal is durable and countable rather than a log line.
     * The payload is kept whole, which is what makes the event replayable once the cause is fixed.
     */
    @Transactional
    public void recordRejection(
            String eventId,
            String eventType,
            String transactionReference,
            BigDecimal amount,
            RejectionReason reason,
            String payload
    ) {
        processedEvents.saveAndFlush(ProcessedEvent.rejected(
                eventId, eventType, transactionReference, amount, reason, payload
        ));
    }
}
