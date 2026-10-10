package com.flowwallet.payment.payout;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, Long> {
    Optional<PayoutAccount> findByUserId(String userId);

    /**
     * Locks the row before an update, so two writers never overwrite each other's changes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PayoutAccount a where a.id = :id")
    Optional<PayoutAccount> lockById(@Param("id") Long id);
}
