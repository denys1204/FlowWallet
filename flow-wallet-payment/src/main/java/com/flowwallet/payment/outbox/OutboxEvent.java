package com.flowwallet.payment.outbox;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_events_seq_gen")
    @SequenceGenerator(name = "outbox_events_seq_gen", sequenceName = "outbox_events_seq", allocationSize = 50)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    /**
     * A row waiting for its first send. Every later change of state is a conditional UPDATE in
     * {@link OutboxEventRepository}, so the entity has no mutators.
     */
    public static OutboxEvent pending(String aggregateType, String aggregateId, String eventType, String payload) {
        return OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .build();
    }
}
