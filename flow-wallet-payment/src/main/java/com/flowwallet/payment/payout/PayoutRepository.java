package com.flowwallet.payment.payout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {
    Optional<Payout> findByTransactionReference(String transactionReference);
}
