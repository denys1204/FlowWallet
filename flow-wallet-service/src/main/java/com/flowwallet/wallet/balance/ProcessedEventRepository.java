package com.flowwallet.wallet.balance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /**
     * Used only to classify a barrier violation after it has happened. Checking before inserting would be a
     * race between the check and the insert; the unique constraint is the decision.
     */
    Optional<ProcessedEvent> findByEventId(String eventId);
}
