package com.flowwallet.payment.payout;

import com.flowwallet.contract.constant.KafkaConstants;
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
