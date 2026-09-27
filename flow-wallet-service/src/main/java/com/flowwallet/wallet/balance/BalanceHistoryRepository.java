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
     * The newest movements of one wallet, newest first: the first page of its history.
     * See docs/adr/0021-per-wallet-ledger-entry-numbers.md.
     */
    @Query("select h from BalanceHistory h where h.walletId = :walletId order by h.entryNo desc")
    List<BalanceHistory> findNewest(@Param("walletId") Long walletId, Limit limit);

    /**
     * A later page of one wallet's history, newest first, starting just below the entry number {@code before}.
     * <p>
     * It is separate from {@link #findNewest} rather than one query with an optional bound: under a generic plan
     * Postgres cannot turn {@code :before is null or ...} into an index range, and every page would scan all
     * newer rows. With the bound always present, the unique {@code (wallet_id, entry_no)} index serves both the
     * filter and the order, so a page reads about {@code limit} rows however long the history is.
     */
    @Query(
            "select h from BalanceHistory h where h.walletId = :walletId and h.entryNo < :before "
                    + "order by h.entryNo desc"
    )
    List<BalanceHistory> findPageBefore(@Param("walletId") Long walletId, @Param("before") long before, Limit limit);
}
