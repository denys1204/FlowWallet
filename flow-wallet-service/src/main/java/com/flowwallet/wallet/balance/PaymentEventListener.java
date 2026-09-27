package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.wallet.api.AmountPrecision;
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
        PaymentCompletedEvent event = read(
                record,
                PaymentCompletedEvent.class,
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED
        );
        requireEventId(event.eventId(), KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED);

        Optional<RejectionReason> refusal = refusalFor(event);
        if (refusal.isPresent()) {
            reject(event, refusal.get(), record.value());
            return;
        }

        try {
            handler.credit(event);
        } catch (UnknownWalletException e) {
            // Recorded with its payload rather than dead-lettered, so it can be replayed once the wallet exists.
            reject(event, RejectionReason.WALLET_NOT_FOUND, record.value());
        } catch (DataIntegrityViolationException e) {
            // The transaction is already rolled back. A fresh one asks the database which barrier refused it. A
            // switch expression, so a verdict added later fails to compile here instead of falling through and
            // committing the offset of a payment that was never credited.
            DuplicateVerdict verdict = outcomes.classify(event.eventId(), event.transactionReference());
            Optional<RejectionReason> duplicate = switch (verdict) {
                case EVENT_ALREADY_PROCESSED -> {
                    log.info("Event {} was already processed; balance unchanged", event.eventId());
                    yield Optional.empty();
                }

                case REFERENCE_ALREADY_CREDITED -> {
                    log.error(
                            "Transaction {} was already credited by a different event; event {} refused. "
                                    + "Two events for one payment is a producer contract violation.",
                            event.transactionReference(),
                            event.eventId()
                    );
                    yield Optional.of(RejectionReason.DUPLICATE_REFERENCE);
                }

                // Neither barrier: the credit did not happen. Rethrowing sends the record through the retries
                // to the dead-letter topic instead of acknowledging money that never arrived.
                case NOT_A_DUPLICATE -> throw e;
            };
            duplicate.ifPresent(reason -> reject(event, reason, record.value()));
        }
    }

    private void onPaymentFailed(ConsumerRecord<String, String> record) {
        PaymentFailedEvent event = read(record, PaymentFailedEvent.class, KafkaConstants.EVENT_TYPE_PAYMENT_FAILED);
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
     * its amount on trust, so this is the only place an amount that is zero, negative or off its currency's grid
     * is refused as {@code INVALID_AMOUNT} and stored with its payload. Without it an off-grid amount such as
     * 10.123 USD would be credited, and one too fine for the ledger, such as 0.00001, would be rounded to zero,
     * refused by {@code balance_history_amount_positive}, retried and dead-lettered. The envelope comes first,
     * because the grid depends on the currency.
     * See docs/adr/0019-payment-event-amounts-on-the-grid.md.
     */
    private Optional<RejectionReason> refusalFor(PaymentCompletedEvent event) {
        if (isBlank(event.transactionReference()) || isBlank(event.currency()) || isBlank(event.userId())) {
            return Optional.of(RejectionReason.INVALID_ENVELOPE);
        }
        BigDecimal amount = event.amount();
        if (amount == null || amount.signum() <= 0 || !AmountPrecision.isOnGrid(amount, event.currency())) {
            return Optional.of(RejectionReason.INVALID_AMOUNT);
        }
        return Optional.empty();
    }

    /**
     * Recording a refusal meets the event-id barrier too. A redelivered refusal is acknowledged here, so it
     * does not reach the dead-letter topic, which is for records the wallet could not read or could not settle
     * after its retries.
     */
    private void reject(PaymentCompletedEvent event, RejectionReason reason, String payload) {
        log.error("Refused event {} for transaction {}: {}", event.eventId(), event.transactionReference(), reason);
        try {
            outcomes.recordRejection(event, reason, payload);
        } catch (DataIntegrityViolationException e) {
            if (outcomes.classify(event.eventId(), event.transactionReference())
                    == DuplicateVerdict.EVENT_ALREADY_PROCESSED) {
                log.info("Event {} was already refused; the refusal is on record", event.eventId());
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

    /**
     * A record with no value makes {@code readValue} throw {@code IllegalArgumentException}, which is no
     * {@code JacksonException}, and the JSON literal {@code null} parses to a null event. Both are refused here as
     * unreadable, or they would be retried and dead-lettered under a misleading exception.
     */
    private <T> T read(ConsumerRecord<String, String> record, Class<T> type, String eventType) {
        if (record.value() == null) {
            throw UnreadablePaymentEventException.noEvent(eventType);
        }
        T event;
        try {
            event = objectMapper.readValue(record.value(), type);
        } catch (JacksonException e) {
            throw UnreadablePaymentEventException.unparseable(eventType, e);
        }
        if (event == null) {
            throw UnreadablePaymentEventException.noEvent(eventType);
        }
        return event;
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
