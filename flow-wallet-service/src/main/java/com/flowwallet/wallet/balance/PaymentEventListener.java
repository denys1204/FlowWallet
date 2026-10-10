package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.enums.RejectionReason;
import com.flowwallet.wallet.enums.TransactionType;
import com.flowwallet.wallet.withdrawal.UnknownWithdrawalException;
import com.flowwallet.wallet.withdrawal.WithdrawalEventHandler;
import com.flowwallet.wallet.withdrawal.WithdrawalMismatchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Turns payment and payout events into wallet balances and withdrawal statuses, exactly once each.
 * <p>
 * The event's type comes from the {@code eventType} header, never from the shape of the JSON. This class is not
 * transactional: it runs after {@link PaymentEventHandler}'s or {@link WithdrawalEventHandler}'s transaction has
 * committed or rolled back, the only position from which a rolled-back write can be classified.
 * See docs/adr/0010-idempotent-payment-event-consumer.md and
 * docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {
    private final ObjectMapper objectMapper;
    private final PaymentEventHandler handler;
    private final WithdrawalEventHandler withdrawalHandler;
    private final PaymentEventOutcomeStore outcomes;

    @KafkaListener(topics = KafkaConstants.PAYMENT_EVENTS_TOPIC)
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = eventType(record);

        switch (eventType) {
            case KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED -> onPaymentCompleted(record);
            case KafkaConstants.EVENT_TYPE_PAYMENT_FAILED -> onPaymentFailed(record);
            case KafkaConstants.EVENT_TYPE_PAYOUT_PAID -> onPayoutPaid(record);
            case KafkaConstants.EVENT_TYPE_PAYOUT_FAILED -> onPayoutFailed(record);
            case KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED -> onPayoutReturned(record);
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
        EventEnvelope envelope = EventEnvelope.of(event);

        Optional<RejectionReason> refusal = refusalFor(envelope);
        if (refusal.isPresent()) {
            reject(envelope, refusal.get(), record.value());
            return;
        }

        try {
            handler.credit(event);
        } catch (UnknownWalletException e) {
            // Recorded with its payload rather than dead-lettered, so it can be republished under a fresh event id
            // once the wallet exists.
            reject(envelope, RejectionReason.WALLET_NOT_FOUND, record.value());
        } catch (DataIntegrityViolationException e) {
            refuseDuplicate(envelope, TransactionType.DEPOSIT, record.value(), e);
        }
    }

    private void onPaymentFailed(ConsumerRecord<String, String> record) {
        PaymentFailedEvent event = read(record, PaymentFailedEvent.class, KafkaConstants.EVENT_TYPE_PAYMENT_FAILED);
        requireEventId(event.eventId(), KafkaConstants.EVENT_TYPE_PAYMENT_FAILED);

        try {
            handler.recordFailure(event, record.value());
        } catch (DataIntegrityViolationException e) {
            if (outcomes.classify(event.eventId(), event.transactionReference(), null)
                    == DuplicateVerdict.EVENT_ALREADY_PROCESSED) {
                log.info("Failure event {} was already recorded", event.eventId());
                return;
            }
            throw e;
        }
    }

    /**
     * Paid is the one payout event that must name its payout, because the payout exists by then.
     * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
     */
    private void onPayoutPaid(ConsumerRecord<String, String> record) {
        PayoutPaidEvent event = read(record, PayoutPaidEvent.class, KafkaConstants.EVENT_TYPE_PAYOUT_PAID);
        requireEventId(event.eventId(), KafkaConstants.EVENT_TYPE_PAYOUT_PAID);
        EventEnvelope envelope = EventEnvelope.of(event);

        Optional<RejectionReason> refusal = isBlank(event.providerPayoutId())
                ? Optional.of(RejectionReason.INVALID_ENVELOPE)
                : refusalFor(envelope);
        if (refusal.isPresent()) {
            reject(envelope, refusal.get(), record.value());
            return;
        }
        settleWithdrawal(envelope, null, record.value(), () -> withdrawalHandler.recordPaid(event, record.value()));
    }

    private void onPayoutFailed(ConsumerRecord<String, String> record) {
        PayoutFailedEvent event = read(record, PayoutFailedEvent.class, KafkaConstants.EVENT_TYPE_PAYOUT_FAILED);
        requireEventId(event.eventId(), KafkaConstants.EVENT_TYPE_PAYOUT_FAILED);
        EventEnvelope envelope = EventEnvelope.of(event);

        Optional<RejectionReason> refusal = refusalFor(envelope);
        if (refusal.isPresent()) {
            reject(envelope, refusal.get(), record.value());
            return;
        }
        settleWithdrawal(envelope, null, record.value(), () -> withdrawalHandler.recordFailed(event, record.value()));
    }

    private void onPayoutReturned(ConsumerRecord<String, String> record) {
        PayoutReturnedEvent event = read(record, PayoutReturnedEvent.class, KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED);
        requireEventId(event.eventId(), KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED);
        EventEnvelope envelope = EventEnvelope.of(event);

        Optional<RejectionReason> refusal = refusalFor(envelope);
        if (refusal.isPresent()) {
            reject(envelope, refusal.get(), record.value());
            return;
        }
        settleWithdrawal(
                envelope,
                TransactionType.WITHDRAWAL_RETURNED,
                record.value(),
                () -> withdrawalHandler.creditReturn(event)
        );
    }

    /**
     * Runs a payout event's transaction and records what the wallet refused once it has rolled back: a wallet or
     * withdrawal it does not hold, a withdrawal with other terms, or a second return for one reference.
     *
     * @param barrierType the ledger type the transaction writes, or null for an event that moves no money
     */
    private void settleWithdrawal(
            EventEnvelope envelope,
            @Nullable TransactionType barrierType,
            String payload,
            Runnable transaction
    ) {
        try {
            transaction.run();
        } catch (UnknownWalletException e) {
            reject(envelope, RejectionReason.WALLET_NOT_FOUND, payload);
        } catch (UnknownWithdrawalException e) {
            reject(envelope, RejectionReason.WITHDRAWAL_NOT_FOUND, payload);
        } catch (WithdrawalMismatchException e) {
            reject(envelope, RejectionReason.WITHDRAWAL_MISMATCH, payload);
        } catch (DataIntegrityViolationException e) {
            refuseDuplicate(envelope, barrierType, payload, e);
        }
    }

    /**
     * The transaction is already rolled back. A fresh one asks the database which barrier refused it. A switch
     * expression, so a verdict added later fails to compile here instead of falling through and committing the
     * offset of an event whose effect never happened.
     */
    private void refuseDuplicate(
            EventEnvelope envelope,
            @Nullable TransactionType barrierType,
            String payload,
            DataIntegrityViolationException violation
    ) {
        DuplicateVerdict verdict = outcomes.classify(envelope.eventId(), envelope.transactionReference(), barrierType);
        Optional<RejectionReason> duplicate = switch (verdict) {
            case EVENT_ALREADY_PROCESSED -> {
                log.info("Event {} was already processed; nothing changed", envelope.eventId());
                yield Optional.empty();
            }

            case REFERENCE_ALREADY_CREDITED -> {
                log.error(
                        "Transaction {} was already credited by a different event; event {} refused. "
                                + "Two credits for one reference is a producer contract violation.",
                        envelope.transactionReference(),
                        envelope.eventId()
                );
                yield Optional.of(RejectionReason.DUPLICATE_REFERENCE);
            }

            // Neither barrier: the write did not happen. Rethrowing sends the record through the retries to the
            // dead-letter topic instead of acknowledging an effect that never happened.
            case NOT_A_DUPLICATE -> throw violation;
        };
        duplicate.ifPresent(reason -> reject(envelope, reason, payload));
    }

    /**
     * Everything a credit or a withdrawal's status depends on. The event records carry no validation and
     * {@link Wallet#credit} takes its amount on trust, so this is the only place an amount that is zero, negative or
     * off its currency's grid is refused as {@code INVALID_AMOUNT} and stored with its payload. Without it an off-grid
     * amount such as 10.123 USD would be credited, and one too fine for the ledger, such as 0.00001, would be rounded
     * to zero, refused by {@code balance_history_amount_positive}, retried and dead-lettered. The envelope comes first,
     * because the grid depends on the currency.
     * See docs/adr/0019-payment-event-amounts-on-the-grid.md.
     */
    private Optional<RejectionReason> refusalFor(EventEnvelope event) {
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
    private void reject(EventEnvelope event, RejectionReason reason, String payload) {
        log.error("Refused event {} for transaction {}: {}", event.eventId(), event.transactionReference(), reason);
        try {
            outcomes.recordRejection(event, reason, payload);
        } catch (DataIntegrityViolationException e) {
            if (outcomes.classify(event.eventId(), event.transactionReference(), null)
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
