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
     * The movement of one type under a reference. The type is required: a reference owns one movement of each
     * type, and the unique {@code (transaction_reference, type)} is what makes the {@code Optional} safe.
     * <p>
     * Apart from a transfer judging its key under the sender wallet's lock, this is read only to classify a
     * violation after the fact, never as a guard before an insert: the unique index decides.
     * See docs/adr/0007-unique-constraints-decide.md and docs/adr/0012-balances-and-append-only-ledger.md.
     */
    Optional<BalanceHistory> findByTransactionReferenceAndType(String transactionReference, TransactionType type);

    /**
     * A page of movements for one wallet, newest first, starting just below {@code before}, or from the newest
     * when it is null. A cursor rather than an offset keeps pages stable while credits arrive.
     * See docs/adr/0012-balances-and-append-only-ledger.md.
     */
    @Query(
            "select h from BalanceHistory h where h.walletId = :walletId "
                    + "and (:before is null or h.id < :before) order by h.id desc"
    )
    List<BalanceHistory> findPageBefore(@Param("walletId") Long walletId, @Param("before") Long before, Limit limit);
}
