package com.flowwallet.wallet.balance;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BalanceHistoryRepository extends JpaRepository<BalanceHistory, Long> {

    /**
     * Used only to tell one kind of barrier violation from another after the fact — never as a check before
     * inserting. A read-then-write here would be a race; the unique constraint is what actually decides.
     */
    Optional<BalanceHistory> findByTransactionReference(String transactionReference);

    /**
     * A page of movements for one wallet, newest first, starting just below {@code before} — or from the
     * newest when it is null.
     * <p>
     * Keyed by a cursor rather than an offset because the ledger only ever grows at its newest end. A credit
     * arriving between two page requests shifts every offset by one, so an offset-paged client sees a
     * movement twice or misses one entirely. No page size makes that go away.
     */
    @Query("select h from BalanceHistory h where h.walletId = :walletId "
            + "and (:before is null or h.id < :before) order by h.id desc")
    List<BalanceHistory> findPageBefore(@Param("walletId") Long walletId,
                                        @Param("before") Long before,
                                        Limit limit);
}
