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
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus WHERE e.id = :id AND e.status = :expectedStatus")
    int lockForProcessing(@Param("id") Long id, 
                          @Param("newStatus") OutboxStatus newStatus, 
                          @Param("expectedStatus") OutboxStatus expectedStatus);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE OutboxEvent e SET e.status = :status, e.processedAt = :processedAt WHERE e.id = :id")
    void markAsCompleted(@Param("id") Long id, 
                         @Param("status") OutboxStatus status,
                         @Param("processedAt") Instant processedAt);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE OutboxEvent e SET e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failedStatus ELSE :pendingStatus END, " +
           "e.retryCount = e.retryCount + 1, e.errorMessage = :errorMessage WHERE e.id = :id")
    void incrementRetryOrFail(@Param("id") Long id, 
                              @Param("errorMessage") String errorMessage,
                              @Param("maxRetries") int maxRetries,
                              @Param("failedStatus") OutboxStatus failedStatus,
                              @Param("pendingStatus") OutboxStatus pendingStatus);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :newStatus WHERE e.status = :expectedStatus")
    int resetStuckEvents(@Param("newStatus") OutboxStatus newStatus, 
                         @Param("expectedStatus") OutboxStatus expectedStatus);

    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent e WHERE e.status IN :statuses AND e.createdAt < :before")
    int deleteOldEvents(@Param("statuses") Collection<OutboxStatus> statuses,
                        @Param("before") Instant before);
}
