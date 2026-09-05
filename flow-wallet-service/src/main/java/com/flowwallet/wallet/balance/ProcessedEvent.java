package com.flowwallet.wallet.balance;

import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.enums.ProcessedEventOutcome;
import com.flowwallet.wallet.enums.RejectionReason;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per event the wallet has seen, and the first of the two barriers protecting a balance.
 * <p>
 * The unique {@code event_id} is what makes at-least-once delivery safe: a redelivery cannot insert a
 * second row, so it cannot credit a second time. It is also the audit trail — every event is accounted
 * for, including the ones refused, which is what keeps a rejection from being a silent drop.
 * <p>
 * Append-only: no {@code @Version}, no {@code @UpdateTimestamp}, no mutators.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "processed_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "processed_events_seq_gen")
    @SequenceGenerator(name = "processed_events_seq_gen", sequenceName = "processed_events_seq", allocationSize = 50)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "transaction_reference", length = 64)
    private String transactionReference;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private ProcessedEventOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 20)
    private RejectionReason rejectionReason;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    /**
     * A payment credited to a wallet. The payload is not kept: the movement it caused is already recorded
     * in {@link BalanceHistory}, in a form far more useful than the raw JSON.
     */
    public static ProcessedEvent credited(PaymentCompletedEvent event) {
        return ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED)
                .transactionReference(event.transactionReference())
                .amount(event.amount())
                .outcome(ProcessedEventOutcome.CREDITED)
                .build();
    }

    /**
     * A payment that failed at the provider. No balance moves, which is what makes the ordering of a
     * failure and a success for one reference irrelevant. The payload is kept because it carries the
     * provider's reason, and that is what a support question is actually about.
     */
    public static ProcessedEvent failureRecorded(PaymentFailedEvent event, String payload) {
        return ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(KafkaConstants.EVENT_TYPE_PAYMENT_FAILED)
                .transactionReference(event.transactionReference())
                .amount(event.amount())
                .outcome(ProcessedEventOutcome.FAILURE_RECORDED)
                .payload(payload)
                .build();
    }

    /**
     * An event the wallet refused. The payload is kept in full so it can be replayed once the cause is
     * fixed, without depending on Kafka retention or on the payment team reissuing anything.
     */
    public static ProcessedEvent rejected(
            String eventId,
            String eventType,
            String transactionReference,
            BigDecimal amount,
            RejectionReason reason,
            String payload
    ) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .transactionReference(transactionReference)
                .amount(amount)
                .outcome(ProcessedEventOutcome.REJECTED)
                .rejectionReason(reason)
                .payload(payload)
                .build();
    }
}
