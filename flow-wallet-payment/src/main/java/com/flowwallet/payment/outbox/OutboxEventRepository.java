package com.flowwallet.payment.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status "
            + "AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now) ORDER BY e.createdAt ASC")
    List<OutboxEvent> findDispatchable(@Param("status") OutboxStatus status,
                                       @Param("now") Instant now,
                                       Pageable pageable);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus, e.processingStartedAt = :now "
            + "WHERE e.id = :id AND e.status = :expectedStatus")
    int lockForProcessing(@Param("id") Long id,
                          @Param("newStatus") OutboxStatus newStatus,
                          @Param("expectedStatus") OutboxStatus expectedStatus,
                          @Param("now") Instant now);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE OutboxEvent e SET e.status = :status, e.processedAt = :processedAt WHERE e.id = :id")
    void markAsCompleted(@Param("id") Long id, 
                         @Param("status") OutboxStatus status,
                         @Param("processedAt") Instant processedAt);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE OutboxEvent e SET e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failedStatus ELSE :pendingStatus END, " +
           "e.retryCount = e.retryCount + 1, e.errorMessage = :errorMessage, e.nextAttemptAt = :nextAttemptAt WHERE e.id = :id")
    void incrementRetryOrFail(@Param("id") Long id,
                              @Param("errorMessage") String errorMessage,
                              @Param("maxRetries") int maxRetries,
                              @Param("failedStatus") OutboxStatus failedStatus,
                              @Param("pendingStatus") OutboxStatus pendingStatus,
                              @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus WHERE e.status = :expectedStatus")
    int resetStuckEvents(@Param("newStatus") OutboxStatus newStatus,
                         @Param("expectedStatus") OutboxStatus expectedStatus);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus, e.nextAttemptAt = null, e.processingStartedAt = null "
            + "WHERE e.status = :expectedStatus AND e.processingStartedAt < :threshold")
    int resetStuckProcessing(@Param("newStatus") OutboxStatus newStatus,
                             @Param("expectedStatus") OutboxStatus expectedStatus,
                             @Param("threshold") Instant threshold);

    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent e WHERE e.status IN :statuses AND e.createdAt < :before")
    int deleteOldEvents(@Param("statuses") Collection<OutboxStatus> statuses,
                        @Param("before") Instant before);
}
