package com.flowwallet.payment.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
                         @Param("processedAt") java.time.Instant processedAt);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE OutboxEvent e SET e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failedStatus ELSE :pendingStatus END, " +
           "e.retryCount = e.retryCount + 1, e.errorMessage = :errorMessage WHERE e.id = :id")
    void incrementRetryOrFail(@Param("id") Long id, 
                              @Param("errorMessage") String errorMessage,
                              @Param("maxRetries") int maxRetries,
                              @Param("failedStatus") OutboxStatus failedStatus,
                              @Param("pendingStatus") OutboxStatus pendingStatus);
}
