package com.flowwallet.payment.outbox;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.outbox.Outbox;
import com.flowwallet.payment.transaction.PaymentTransaction;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Builds the payment events and queues them through {@link Outbox} inside the transaction that changes the
 * payment's status, which {@code Outbox} requires. See docs/adr/0008-transactional-outbox.md.
 */
@Service
@RequiredArgsConstructor
public class PaymentOutboxService {
    /**
     * Labels a row in Payment Service's own table and never reaches the wire, so it stays out of
     * flow-wallet-contract. See docs/adr/0002-module-boundaries.md.
     */
    static final String AGGREGATE_TYPE_PAYMENT_TRANSACTION = "PaymentTransaction";

    private final Outbox outbox;
    private final PaymentEventMapper eventMapper;

    public void publishPaymentCompleted(PaymentTransaction tx, Instant completedAt) {
        enqueue(tx, eventMapper.toPaymentCompletedEvent(tx, completedAt));
    }

    public void publishPaymentFailed(PaymentTransaction tx, String reason, Instant failedAt) {
        enqueue(tx, eventMapper.toPaymentFailedEvent(tx, reason, failedAt));
    }

    private void enqueue(PaymentTransaction tx, Object event) {
        outbox.enqueue(AGGREGATE_TYPE_PAYMENT_TRANSACTION, tx.getTransactionReference(), eventTypeOf(event), event);
    }

    /**
     * The only place the {@code eventType} header is chosen. The wallet dispatches on the header alone, so a
     * completed header on a failure payload would credit a payment that failed.
     */
    private static String eventTypeOf(Object event) {
        return switch (event) {
            case PaymentCompletedEvent _ -> KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED;
            case PaymentFailedEvent _ -> KafkaConstants.EVENT_TYPE_PAYMENT_FAILED;
            default -> throw new IllegalArgumentException("No eventType for " + event.getClass().getName());
        };
    }
}
