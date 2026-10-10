package com.flowwallet.payment.payout;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The executor's claim queries write their statuses into the query, as the outbox's do, so the predicate matches
 * {@code idx_payouts_due}. A claim writes {@code claimed_until}, the end of its lease and its ownership token, and the
 * step's {@code *_attempted_at} only if no earlier claim set it, and raises {@code version}, so a save of a copy read
 * before the claim fails its optimistic check. They run in the caller's transaction.
 * See docs/adr/0018-outbox-sends-own-their-claim.md and
 * docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public interface PayoutRepository extends JpaRepository<Payout, Long> {
    Optional<Payout> findByTransactionReference(String transactionReference);

    /**
     * What the platform's balance still owes to payouts that have no transfer yet: those waiting for one, and those
     * an operator holds before it. Their money is still in the platform's balance, so the reserve counts it as spent.
     * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
     */
    @Query("""
            select coalesce(sum(p.amount), 0) from Payout p
            where p.currency = :currency
              and (p.status = com.flowwallet.payment.payout.PayoutStatus.ACCEPTED
                   or (p.status = com.flowwallet.payment.payout.PayoutStatus.NEEDS_ATTENTION
                       and p.attentionFrom = com.flowwallet.payment.payout.PayoutStatus.ACCEPTED))
            """)
    BigDecimal sumNotYetTransferred(@Param("currency") String currency);

    /**
     * The rows due in an executor step whose lease, if any, has run out. A selected row is only a hint: its claim
     * checks the same conditions again.
     */
    @Query("""
            select new com.flowwallet.payment.payout.DuePayout(p.id, p.status) from Payout p
            where p.status in (com.flowwallet.payment.payout.PayoutStatus.ACCEPTED,
                               com.flowwallet.payment.payout.PayoutStatus.TRANSFERRED,
                               com.flowwallet.payment.payout.PayoutStatus.REVERSING)
              and p.nextAttemptAt <= :now
              and (p.claimedUntil is null or p.claimedUntil < :now)
            order by p.nextAttemptAt, p.id
            """)
    List<DuePayout> findDue(@Param("now") Instant now, Pageable page);

    /**
     * @return 1 if this caller now owns the transfer step, 0 if the row moved, is not due or is claimed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Payout p
            set p.claimedUntil = :claimedUntil,
                p.transferAttemptedAt = coalesce(p.transferAttemptedAt, :claimedAt),
                p.version = p.version + 1
            where p.id = :id
              and p.status = com.flowwallet.payment.payout.PayoutStatus.ACCEPTED
              and p.nextAttemptAt <= :claimedAt
              and (p.claimedUntil is null or p.claimedUntil < :claimedAt)
            """)
    int claimTransfer(
            @Param("id") Long id,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimedUntil") Instant claimedUntil
    );

    /**
     * @return 1 if this caller now owns the payout step, 0 if the row moved, is not due or is claimed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Payout p
            set p.claimedUntil = :claimedUntil,
                p.payoutAttemptedAt = coalesce(p.payoutAttemptedAt, :claimedAt),
                p.version = p.version + 1
            where p.id = :id
              and p.status = com.flowwallet.payment.payout.PayoutStatus.TRANSFERRED
              and p.nextAttemptAt <= :claimedAt
              and (p.claimedUntil is null or p.claimedUntil < :claimedAt)
            """)
    int claimPayout(
            @Param("id") Long id,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimedUntil") Instant claimedUntil
    );

    /**
     * @return 1 if this caller now owns the reversal step, 0 if the row moved, is not due or is claimed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Payout p
            set p.claimedUntil = :claimedUntil,
                p.reversalAttemptedAt = coalesce(p.reversalAttemptedAt, :claimedAt),
                p.version = p.version + 1
            where p.id = :id
              and p.status = com.flowwallet.payment.payout.PayoutStatus.REVERSING
              and p.nextAttemptAt <= :claimedAt
              and (p.claimedUntil is null or p.claimedUntil < :claimedAt)
            """)
    int claimReversal(
            @Param("id") Long id,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimedUntil") Instant claimedUntil
    );

    /**
     * Locks the row before an outcome is recorded, so the token it is checked against cannot change underneath.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payout p where p.id = :id")
    Optional<Payout> lockById(@Param("id") Long id);
}
