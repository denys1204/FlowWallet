package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.enums.ProcessedEventOutcome;
import com.flowwallet.wallet.enums.RejectionReason;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per event the wallet has settled, refusals included. The unique {@code event_id} is the barrier that
 * stops a redelivery from crediting twice. Append-only: no {@code @Version}, no {@code @UpdateTimestamp}, no
 * mutators. See docs/adr/0010-idempotent-payment-event-consumer.md.
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
     * A payment credited to a wallet. The payload is not kept, because {@link BalanceHistory} records the movement.
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
     * A payment that failed at the provider. No balance moves, and the payload is kept for the failure reason it
     * carries.
     */
    public static ProcessedEvent failureRecorded(PaymentFailedEvent event, String payload) {
        return ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(KafkaConstants.EVENT_TYPE_PAYMENT_FAILED)
                .transactionReference(event.transactionReference())
                .amount(storable(event.amount()))
                .outcome(ProcessedEventOutcome.FAILURE_RECORDED)
                .payload(payload)
                .build();
    }

    /**
     * A completed payment the wallet refused. The payload is kept in full so it can be replayed once the cause is
     * fixed.
     */
    public static ProcessedEvent rejected(PaymentCompletedEvent event, RejectionReason reason, String payload) {
        return ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED)
                .transactionReference(event.transactionReference())
                .amount(storable(event.amount()))
                .outcome(ProcessedEventOutcome.REJECTED)
                .rejectionReason(reason)
                .payload(payload)
                .build();
    }

    /**
     * The amount if {@code NUMERIC(19,4)} holds it exactly, otherwise NULL. Postgres would round a finer amount
     * into a figure the event never carried and refuse a larger one, which would send a readable refusal through
     * the retries to the dead-letter topic. The payload keeps the amount as it was sent.
     */
    private static BigDecimal storable(BigDecimal amount) {
        return amount != null && AmountPrecision.fitsLedger(amount) ? amount : null;
    }
}
