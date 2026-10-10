package com.flowwallet.payment.payout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, Long> {
    Optional<PayoutAccount> findByUserId(String userId);
}
