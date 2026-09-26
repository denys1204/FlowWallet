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
 * The event's type comes from the {@code eventType} header, never from the shape of the JSON. This class is not
 * transactional: it runs after {@link PaymentEventHandler}'s transaction has committed or rolled back, the only
 * position from which a rolled-back write can be classified. See docs/adr/0010-idempotent-payment-event-consumer.md.
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
        } catch (UnknownWalletException e) {
            // Recorded with its payload rather than dead-lettered, so it can be replayed once the wallet exists.
            reject(event.eventId(), KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED, event.transactionReference(),
                    event.amount(), RejectionReason.WALLET_NOT_FOUND, record.value());
        } catch (DataIntegrityViolationException e) {
            // The transaction is already rolled back. A fresh one asks the database which barrier refused it.
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

                // Neither barrier: the credit did not happen. Rethrowing sends the record through the retries
                // to the dead-letter topic instead of acknowledging money that never arrived.
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
     * Everything the credit depends on. The event records carry no validation and {@link Wallet#credit} takes
     * its amount on trust, so this is the only place a negative amount, which would debit the wallet, is caught.
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
     * Recording a refusal meets the event-id barrier too. A redelivered refusal is acknowledged here, so it
     * does not reach the dead-letter topic, which is for records the wallet could not read.
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
