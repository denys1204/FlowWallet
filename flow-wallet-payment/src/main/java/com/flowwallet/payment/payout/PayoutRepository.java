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

    /**
     * The row a Stripe payout belongs to, locked for its settlement.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payout p where p.stripePayoutId = :payoutId")
    Optional<Payout> lockByStripePayoutId(@Param("payoutId") String payoutId);

    /**
     * The row a payout's metadata names, for a payout Stripe reports before the executor recorded its id.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payout p where p.transactionReference = :reference")
    Optional<Payout> lockByTransactionReference(@Param("reference") String reference);

    /**
     * Payouts waiting for Stripe whose last look, or creation, is older than the cutoff, the least recently looked at
     * first. The order follows {@code idx_payouts_created}.
     */
    @Query("""
            select new com.flowwallet.payment.payout.ReconcilablePayout(
                p.id, p.transactionReference, p.status, p.stripeAccountId, p.stripePayoutId)
            from Payout p
            where p.status = com.flowwallet.payment.payout.PayoutStatus.PAYOUT_CREATED
              and coalesce(p.lastReconciledAt, p.createdAt) <= :lookedAtBefore
            order by p.lastReconciledAt asc nulls first, p.id
            """)
    List<ReconcilablePayout> findCreatedToReconcile(@Param("lookedAtBefore") Instant lookedAtBefore, Pageable page);

    /**
     * Paid payouts still inside the window in which Stripe can fail them, whose last look is older than the cutoff.
     * A range on {@code idx_payouts_paid}.
     */
    @Query("""
            select new com.flowwallet.payment.payout.ReconcilablePayout(
                p.id, p.transactionReference, p.status, p.stripeAccountId, p.stripePayoutId)
            from Payout p
            where p.status = com.flowwallet.payment.payout.PayoutStatus.PAID
              and p.paidAt > :paidAfter
              and coalesce(p.lastReconciledAt, p.paidAt) <= :lookedAtBefore
            order by p.lastReconciledAt asc nulls first, p.paidAt, p.id
            """)
    List<ReconcilablePayout> findPaidToReconcile(
            @Param("paidAfter") Instant paidAfter,
            @Param("lookedAtBefore") Instant lookedAtBefore,
            Pageable page
    );

    /**
     * The reconciler's claim: it moves {@code last_reconciled_at} only if no other instance looked at the row since
     * the cutoff and the row is still in the status it was selected in. It does not raise {@code version}, so it never
     * fails a save by the webhook or the executor.
     *
     * @return 1 if this caller may ask Stripe about the row
     */
    @Modifying
    @Query("""
            update Payout p set p.lastReconciledAt = :now
            where p.id = :id and p.status = :status
              and (p.lastReconciledAt is null or p.lastReconciledAt <= :lookedAtBefore)
            """)
    int claimForReconciliation(
            @Param("id") Long id,
            @Param("status") PayoutStatus status,
            @Param("now") Instant now,
            @Param("lookedAtBefore") Instant lookedAtBefore
    );
}
