package com.flowwallet.wallet.balance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    /**
     * Read only to classify a barrier violation after it has happened, never as a check before the insert.
     * See docs/adr/0007-unique-constraints-decide.md.
     */
    Optional<ProcessedEvent> findByEventId(String eventId);
}
