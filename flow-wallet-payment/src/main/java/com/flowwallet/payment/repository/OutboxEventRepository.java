package com.flowwallet.payment.repository;

import com.flowwallet.payment.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByProcessedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}
