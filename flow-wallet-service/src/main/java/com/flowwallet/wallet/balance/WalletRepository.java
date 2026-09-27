package com.flowwallet.wallet.balance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    /**
     * Reads a wallet without locking it, for code that writes no balance. Never call it before
     * {@link #lockByUserIdAndCurrency} in the same transaction. See docs/adr/0011-wallet-row-locking.md.
     */
    Optional<Wallet> findByUserIdAndCurrency(String userId, String currency);

    List<Wallet> findByUserIdOrderByCurrency(String userId);

    /**
     * Loads a wallet and holds its row lock for the rest of the transaction. Every balance write goes through
     * this: the wallet a payment credits and both wallets of a transfer.
     * <p>
     * A transaction that locks several wallets locks them in ascending {@code (user_id, currency)}, or two
     * opposite transfers deadlock. Nothing may load the wallet without a lock earlier in the same transaction:
     * this query would return that managed instance, and Hibernate would throw {@code StaleObjectStateException}
     * whenever another writer committed in between. See docs/adr/0011-wallet-row-locking.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId and w.currency = :currency")
    Optional<Wallet> lockByUserIdAndCurrency(@Param("userId") String userId, @Param("currency") String currency);
}
