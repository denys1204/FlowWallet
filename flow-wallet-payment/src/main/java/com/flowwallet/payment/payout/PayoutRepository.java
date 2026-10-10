package com.flowwallet.payment.payout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

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
}
