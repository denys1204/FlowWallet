package com.flowwallet.wallet.withdrawal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
    Optional<Withdrawal> findByTransactionReference(String transactionReference);
}
