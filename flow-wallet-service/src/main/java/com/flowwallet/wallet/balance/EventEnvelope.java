package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;

import java.math.BigDecimal;

/**
 * The fields of a payment or payout event that the wallet judges before it settles anything, and records with a
 * refusal: the event, its reference, whose wallet and how much. Built from the typed event, so the refusal path is
 * the same for every type. See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        String transactionReference,
        String userId,
        String currency,
        BigDecimal amount
) {
    public static EventEnvelope of(PaymentCompletedEvent event) {
        return new EventEnvelope(
                event.eventId(),
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED,
                event.transactionReference(),
                event.userId(),
                event.currency(),
                event.amount()
        );
    }

    public static EventEnvelope of(PayoutPaidEvent event) {
        return new EventEnvelope(
                event.eventId(),
                KafkaConstants.EVENT_TYPE_PAYOUT_PAID,
                event.transactionReference(),
                event.userId(),
                event.currency(),
                event.amount()
        );
    }

    public static EventEnvelope of(PayoutFailedEvent event) {
        return new EventEnvelope(
                event.eventId(),
                KafkaConstants.EVENT_TYPE_PAYOUT_FAILED,
                event.transactionReference(),
                event.userId(),
                event.currency(),
                event.amount()
        );
    }

    public static EventEnvelope of(PayoutReturnedEvent event) {
        return new EventEnvelope(
                event.eventId(),
                KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED,
                event.transactionReference(),
                event.userId(),
                event.currency(),
                event.amount()
        );
    }
}
