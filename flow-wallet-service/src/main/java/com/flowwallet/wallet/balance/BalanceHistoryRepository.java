package com.flowwallet.wallet.balance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BalanceHistoryRepository extends JpaRepository<BalanceHistory, Long> {

    /**
     * Used only to tell one kind of barrier violation from another after the fact — never as a check before
     * inserting. A read-then-write here would be a race; the unique constraint is what actually decides.
     */
    Optional<BalanceHistory> findByTransactionReference(String transactionReference);
}
