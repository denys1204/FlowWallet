package com.flowwallet.wallet.balance;

import com.flowwallet.wallet.enums.TransactionType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BalanceHistoryRepository extends JpaRepository<BalanceHistory, Long> {

    /**
     * The movement of one type under a reference. Used to tell one kind of barrier violation from another
     * after the fact, never as a check before inserting: a read-then-write here would be a race, and the unique
     * constraint is what actually decides.
     * <p>
     * The type is part of the lookup because a reference alone does not name one row. The ledger is unique on
     * {@code (transaction_reference, type)} (migration 005), so a reference may own one movement of each type,
     * and that same key is what makes the {@code Optional} safe. A finder by reference alone would
     * throw {@link org.springframework.dao.IncorrectResultSizeDataAccessException} the first time two
     * movements share a reference, and would have to be replaced by a list that every caller then filters.
     */
    Optional<BalanceHistory> findByTransactionReferenceAndType(String transactionReference, TransactionType type);

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
