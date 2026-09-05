package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.wallet.enums.RejectionReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Turns payment events into wallet balances, exactly once each.
 * <p>
 * Idempotency has two independent barriers, and both are needed. The unique {@code event_id} stops a
 * redelivery of the same message, which at-least-once delivery guarantees will happen. The unique
 * {@code transaction_reference} on the ledger stops a second credit for one payment even if it arrives
 * under a different event id — which is a producer defect rather than a redelivery, and the case where
 * money is actually at stake.
 * <p>
 * The event's type comes from the {@code eventType} header, never from the shape of the JSON. Guessing from
 * the fields present breaks the first time the schema grows a field, and a payment system is a poor place
 * to learn that.
 * <p>
 * This class is not transactional. It runs after {@link PaymentEventHandler}'s transaction has already
 * committed or rolled back, which is the only position from which a rolled-back write can be examined.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {
    private final ObjectMapper objectMapper;
    private final PaymentEventHandler handler;
    private final PaymentEventOutcomeStore outcomes;

    @KafkaListener(topics = KafkaConstants.PAYMENT_EVENTS_TOPIC)
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = eventType(record);

        switch (eventType) {
            case KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED -> onPaymentCompleted(record);
            case KafkaConstants.EVENT_TYPE_PAYMENT_FAILED -> onPaymentFailed(record);
            default -> throw UnreadablePaymentEventException.unknownEventType(eventType);
        }
    }

    private void onPaymentCompleted(ConsumerRecord<String, String> record) {
        PaymentCompletedEvent event = read(record, PaymentCompletedEvent.class,
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED);
        requireEventId(event.eventId(), KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED);

        Optional<RejectionReason> refusal = refusalFor(event.transactionReference(), event.currency(),
                event.userId(), event.amount());
        if (refusal.isPresent()) {
            reject(event.eventId(), KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED, event.transactionReference(),
                    event.amount(), refusal.get(), record.value());
            return;
        }

        try {
            handler.credit(event);
        } catch (DataIntegrityViolationException e) {
            // The transaction is already rolled back. Ask the database which barrier refused it, from a
            // fresh transaction, rather than reading anything off the exception.
            switch (outcomes.classify(event.eventId(), event.transactionReference())) {
                case EVENT_ALREADY_PROCESSED -> log.info(
                        "Event {} was already processed; balance unchanged", event.eventId());

                case REFERENCE_ALREADY_CREDITED -> {
                    log.error("Transaction {} was already credited by a different event; event {} refused. "
                                    + "Two events for one payment is a producer contract violation.",
                            event.transactionReference(), event.eventId());
                    reject(event.eventId(), KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED,
                            event.transactionReference(), event.amount(),
                            RejectionReason.DUPLICATE_REFERENCE, record.value());
                }

                // Neither barrier: the credit did not happen and this is not a duplicate. Raising it sends
                // the record to the dead-letter topic rather than acknowledging money that never arrived.
                case NOT_A_DUPLICATE -> throw e;
            }
        }
    }

    private void onPaymentFailed(ConsumerRecord<String, String> record) {
        PaymentFailedEvent event = read(record, PaymentFailedEvent.class,
                KafkaConstants.EVENT_TYPE_PAYMENT_FAILED);
        requireEventId(event.eventId(), KafkaConstants.EVENT_TYPE_PAYMENT_FAILED);

        try {
            handler.recordFailure(event, record.value());
        } catch (DataIntegrityViolationException e) {
            if (outcomes.classify(event.eventId(), event.transactionReference())
                    == DuplicateVerdict.EVENT_ALREADY_PROCESSED) {
                log.info("Failure event {} was already recorded", event.eventId());
                return;
            }
            throw e;
        }
    }

    /**
     * Everything the credit depends on. A null amount would throw deep inside the transaction and a
     * negative one would quietly debit the wallet with a perfectly self-consistent ledger row behind it,
     * which is the worse of the two. Neither event record carries validation annotations, so this is the
     * only place either is caught.
     */
    private Optional<RejectionReason> refusalFor(String transactionReference, String currency, String userId,
                                                 BigDecimal amount) {
        if (isBlank(transactionReference) || isBlank(currency) || isBlank(userId)) {
            return Optional.of(RejectionReason.INVALID_ENVELOPE);
        }
        if (amount == null || amount.signum() <= 0) {
            return Optional.of(RejectionReason.INVALID_AMOUNT);
        }
        return Optional.empty();
    }

    /**
     * Recording a refusal is a write like any other, so it meets the same barrier: redelivering an event the
     * wallet already refused violates the unique event id. Without this guard that record would be
     * dead-lettered, and the topic meant for records the wallet could not read would fill with ones it read
     * perfectly well and deliberately declined.
     */
    private void reject(String eventId, String eventType, String transactionReference, BigDecimal amount,
                        RejectionReason reason, String payload) {
        log.error("Refused event {} for transaction {}: {}", eventId, transactionReference, reason);
        try {
            outcomes.recordRejection(eventId, eventType, transactionReference, amount, reason, payload);
        } catch (DataIntegrityViolationException e) {
            if (outcomes.classify(eventId, transactionReference) == DuplicateVerdict.EVENT_ALREADY_PROCESSED) {
                log.info("Event {} was already refused; the refusal is on record", eventId);
                return;
            }
            throw e;
        }
    }

    private String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaConstants.HEADER_EVENT_TYPE);
        if (header == null || header.value() == null) {
            throw UnreadablePaymentEventException.missingEventType();
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private <T> T read(ConsumerRecord<String, String> record, Class<T> type, String eventType) {
        try {
            return objectMapper.readValue(record.value(), type);
        } catch (JacksonException e) {
            throw UnreadablePaymentEventException.unparseable(eventType, e);
        }
    }

    private void requireEventId(String eventId, String eventType) {
        if (isBlank(eventId)) {
            throw UnreadablePaymentEventException.missingEventId(eventType);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
