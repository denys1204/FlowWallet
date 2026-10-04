package com.flowwallet.payment.transaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);

    Optional<PaymentTransaction> findByProviderTransactionId(String providerTransactionId);

    boolean existsByProviderEventId(String providerEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.id = :id")
    Optional<PaymentTransaction> lockById(@Param("id") Long id);

    /**
     * Payments the provider may have settled without a webhook reaching this service: PENDING, or FAILED, since a
     * declined intent can still be paid; initiated, so there is an intent to ask about; created inside the window;
     * and not looked at since {@code lookedAtBefore}. A FAILED row that the reconciler itself failed belongs to a
     * canceled intent, which cannot be paid any more; {@code reconciledEventPattern} matches its event id. Payments
     * never looked at come first, then the least recently looked at, so abandoned checkouts cannot hold back a new
     * payment. See docs/adr/0032-pending-payments-are-rechecked-with-the-provider.md.
     */
    @Query(
            "SELECT t FROM PaymentTransaction t "
                    + "WHERE t.status IN (com.flowwallet.payment.transaction.TransactionStatus.PENDING, "
                    + "com.flowwallet.payment.transaction.TransactionStatus.FAILED) "
                    + "AND t.providerTransactionId IS NOT NULL "
                    + "AND t.createdAt <= :createdBefore AND t.createdAt > :createdAfter "
                    + "AND (t.lastReconciledAt IS NULL OR t.lastReconciledAt <= :lookedAtBefore) "
                    + "AND NOT (t.status = com.flowwallet.payment.transaction.TransactionStatus.FAILED "
                    + "AND t.providerEventId LIKE :reconciledEventPattern) "
                    + "ORDER BY t.lastReconciledAt ASC NULLS FIRST, t.createdAt ASC"
    )
    List<PaymentTransaction> findReconcilable(
            @Param("createdBefore") Instant createdBefore,
            @Param("createdAfter") Instant createdAfter,
            @Param("lookedAtBefore") Instant lookedAtBefore,
            @Param("reconciledEventPattern") String reconciledEventPattern,
            Pageable pageable
    );

    /**
     * Marks the payment as looked at now, if nobody has looked at it since {@code lookedAtBefore}. Another instance
     * that selected the same row then gets 0 and leaves it. The update is a statement, not an entity save, so it
     * changes no version and never collides with a webhook's optimistic lock.
     *
     * @return 1 if this caller may ask the provider about the payment, 0 if another one already has
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            "UPDATE PaymentTransaction t SET t.lastReconciledAt = :now "
                    + "WHERE t.id = :id AND (t.lastReconciledAt IS NULL OR t.lastReconciledAt <= :lookedAtBefore)"
    )
    int claimForReconciliation(
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("lookedAtBefore") Instant lookedAtBefore
    );
}
