package com.flowwallet.wallet.balance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * Resolves the wallet a payment belongs to and holds it for the rest of the transaction. The pair is the
     * wallet's natural key and carries a unique constraint, so this returns at most one row, and a payment
     * can never name a wallet whose currency disagrees with its own.
     * <p>
     * The lock is what keeps concurrent credits to one wallet cheap. A balance is read, added to and written
     * back, so two threads reading the same version both write it and one loses — and because the partition
     * key is the transaction reference rather than the wallet, several threads on one wallet is ordinary
     * rather than exotic. Optimistic locking answers that by failing the loser, which here means a rolled-back
     * transaction and a Kafka redelivery for something a few microseconds of waiting resolves. Worse, a busy
     * wallet can lose often enough to exhaust the retry budget and dead-letter a payment that was confirmed.
     * <p>
     * {@code @Version} stays on the entity as a backstop for any path that does not take this lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId and w.currency = :currency")
    Optional<Wallet> lockByUserIdAndCurrency(@Param("userId") String userId, @Param("currency") String currency);
}
