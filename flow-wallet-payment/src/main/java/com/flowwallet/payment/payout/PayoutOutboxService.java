package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PayoutFailedEvent;
import com.flowwallet.contract.event.PayoutPaidEvent;
import com.flowwallet.contract.event.PayoutReturnedEvent;
import com.flowwallet.outbox.Outbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the payout events and queues them through {@link Outbox} inside the transaction that changes the payout's
 * status, which {@code Outbox} requires.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@Service
@RequiredArgsConstructor
public class PayoutOutboxService {
    /**
     * Labels a row in Payment Service's own table and never reaches the wire, so it stays out of
     * flow-wallet-contract. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
     */
    static final String AGGREGATE_TYPE_PAYOUT = "Payout";

    private final Outbox outbox;

    /**
     * The event that tells the wallet the payout reached the bank. It moves no money, and a later Failed event can
     * still follow it, since a paid payout can fail days later.
     */
    public void publishPaid(Payout payout, Instant paidAt) {
        PayoutPaidEvent event = new PayoutPaidEvent(
                UUID.randomUUID().toString(),
                KafkaConstants.PAYOUT_EVENT_SCHEMA_VERSION,
                payout.getTransactionReference(),
                payout.getStripePayoutId(),
                payout.getAmount(),
                payout.getCurrency(),
                payout.getUserId(),
                paidAt
        );
        outbox.enqueue(
                AGGREGATE_TYPE_PAYOUT,
                payout.getTransactionReference(),
                KafkaConstants.EVENT_TYPE_PAYOUT_PAID,
                event
        );
    }

    /**
     * The event that tells the wallet the money is coming back, written once a reversal is certain. It moves no
     * money; the credit waits for {@link #publishReturned}. The payout id is null when no payout was made.
     */
    public void publishFailed(Payout payout, Instant failedAt) {
        PayoutFailedEvent event = new PayoutFailedEvent(
                UUID.randomUUID().toString(),
                KafkaConstants.PAYOUT_EVENT_SCHEMA_VERSION,
                payout.getTransactionReference(),
                payout.getStripePayoutId(),
                payout.getAmount(),
                payout.getCurrency(),
                payout.getUserId(),
                payout.getReasonCode().name(),
                payout.getFailureCode(),
                failedAt
        );
        outbox.enqueue(
                AGGREGATE_TYPE_PAYOUT,
                payout.getTransactionReference(),
                KafkaConstants.EVENT_TYPE_PAYOUT_FAILED,
                event
        );
    }

    /**
     * The event that credits the withdrawal back. Its id is fixed here, when the row is written, so every send of the
     * row carries the same one. See docs/adr/0009-payment-event-contract.md.
     */
    public void publishReturned(Payout payout, Instant returnedAt) {
        PayoutReturnedEvent event = new PayoutReturnedEvent(
                UUID.randomUUID().toString(),
                KafkaConstants.PAYOUT_EVENT_SCHEMA_VERSION,
                payout.getTransactionReference(),
                payout.getStripePayoutId(),
                payout.getAmount(),
                payout.getCurrency(),
                payout.getUserId(),
                payout.getReasonCode().name(),
                returnedAt
        );
        outbox.enqueue(
                AGGREGATE_TYPE_PAYOUT,
                payout.getTransactionReference(),
                KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED,
                event
        );
    }
}
