package com.flowwallet.payment.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Every status transition is one conditional UPDATE with its statuses written into the query. A sender acts on a
 * row only while {@code processing_started_at} still holds the value its claim wrote, and every exit from
 * PROCESSING clears that column. See docs/adr/0018-outbox-sends-own-their-claim.md.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    @Query(
            "SELECT e FROM OutboxEvent e WHERE e.status = com.flowwallet.payment.outbox.OutboxStatus.PENDING "
                    + "AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now) ORDER BY e.createdAt ASC"
    )
    List<OutboxEvent> findDispatchable(@Param("now") Instant now, Pageable pageable);

    /**
     * Moves a PENDING row to PROCESSING and records {@code claimedAt}, which later updates match on.
     *
     * @return 1 if this caller owns the row, 0 if another sender has it or it is not PENDING
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.PROCESSING, "
                    + "e.processingStartedAt = :claimedAt "
                    + "WHERE e.id = :id AND e.status = com.flowwallet.payment.outbox.OutboxStatus.PENDING"
    )
    int claim(@Param("id") Long id, @Param("claimedAt") Instant claimedAt);

    /**
     * Records a delivered send.
     *
     * @return 0 if the claim was lost to a reset and another sender may own the row
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.COMPLETED, "
                    + "e.processedAt = :processedAt, e.processingStartedAt = null "
                    + "WHERE e.id = :id AND e.status = com.flowwallet.payment.outbox.OutboxStatus.PROCESSING "
                    + "AND e.processingStartedAt = :claimedAt"
    )
    int markCompleted(
            @Param("id") Long id,
            @Param("claimedAt") Instant claimedAt,
            @Param("processedAt") Instant processedAt
    );

    /**
     * Counts a failed attempt and returns the row to PENDING until {@code nextAttemptAt}.
     *
     * @return 0 if the claim was lost to a reset and another sender may own the row
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.PENDING, "
                    + "e.retryCount = e.retryCount + 1, e.errorMessage = :errorMessage, "
                    + "e.nextAttemptAt = :nextAttemptAt, e.processingStartedAt = null "
                    + "WHERE e.id = :id AND e.status = com.flowwallet.payment.outbox.OutboxStatus.PROCESSING "
                    + "AND e.processingStartedAt = :claimedAt"
    )
    int scheduleRetry(
            @Param("id") Long id,
            @Param("claimedAt") Instant claimedAt,
            @Param("errorMessage") String errorMessage,
            @Param("nextAttemptAt") Instant nextAttemptAt
    );

    /**
     * Counts the last allowed attempt and moves the row to FAILED, the dead-letter store.
     *
     * @return 0 if the claim was lost to a reset and another sender may own the row
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.FAILED, "
                    + "e.retryCount = e.retryCount + 1, e.errorMessage = :errorMessage, "
                    + "e.nextAttemptAt = null, e.processingStartedAt = null "
                    + "WHERE e.id = :id AND e.status = com.flowwallet.payment.outbox.OutboxStatus.PROCESSING "
                    + "AND e.processingStartedAt = :claimedAt"
    )
    int markFailed(
            @Param("id") Long id,
            @Param("claimedAt") Instant claimedAt,
            @Param("errorMessage") String errorMessage
    );

    /**
     * Returns a claimed row to PENDING without counting an attempt, for a send cut short by an interrupt.
     *
     * @return 0 if the claim was lost to a reset and another sender may own the row
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.PENDING, "
                    + "e.processingStartedAt = null "
                    + "WHERE e.id = :id AND e.status = com.flowwallet.payment.outbox.OutboxStatus.PROCESSING "
                    + "AND e.processingStartedAt = :claimedAt"
    )
    int releaseClaim(@Param("id") Long id, @Param("claimedAt") Instant claimedAt);

    @Modifying
    @Transactional
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.PENDING, "
                    + "e.processingStartedAt = null "
                    + "WHERE e.status = com.flowwallet.payment.outbox.OutboxStatus.PROCESSING"
    )
    int resetAllProcessingToPending();

    @Modifying
    @Transactional
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.PENDING, "
                    + "e.nextAttemptAt = null, e.processingStartedAt = null "
                    + "WHERE e.status = com.flowwallet.payment.outbox.OutboxStatus.PROCESSING "
                    + "AND e.processingStartedAt < :threshold"
    )
    int resetProcessingClaimedBefore(@Param("threshold") Instant threshold);

    /**
     * COMPLETED only: a FAILED row is the only record of an event that never reached Kafka.
     */
    @Modifying
    @Transactional
    @Query(
            "DELETE FROM OutboxEvent e WHERE e.status = com.flowwallet.payment.outbox.OutboxStatus.COMPLETED "
                    + "AND e.createdAt < :before"
    )
    int deleteCompletedBefore(@Param("before") Instant before);

    long countByStatus(OutboxStatus status);

    @Modifying
    @Transactional
    @Query(
            "UPDATE OutboxEvent e SET e.status = com.flowwallet.payment.outbox.OutboxStatus.PENDING, "
                    + "e.retryCount = 0, e.errorMessage = null, e.nextAttemptAt = null, e.processingStartedAt = null "
                    + "WHERE e.status = com.flowwallet.payment.outbox.OutboxStatus.FAILED"
    )
    int requeueFailed();
}
