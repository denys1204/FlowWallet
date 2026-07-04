package com.flowwallet.payment.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);
    
    Optional<PaymentTransaction> findByProviderTransactionId(String providerTransactionId);
    
    boolean existsByProviderEventId(String providerEventId);
}
