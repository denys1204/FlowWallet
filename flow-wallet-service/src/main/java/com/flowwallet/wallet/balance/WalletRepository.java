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
     * Reads a wallet without locking it. Separate from {@link #lockByUserIdAndCurrency} on purpose: reusing
     * the locking finder for reads would take a row lock on every balance check and queue them behind
     * whatever credit is in flight, for no benefit — a read has nothing to lose a race about.
     */
    Optional<Wallet> findByUserIdAndCurrency(String userId, String currency);

    /**
     * Every wallet the caller holds. Currency-addressed URLs leave a client no way to discover which
     * currencies it holds after a fresh session, so this is part of the addressing scheme rather than an
     * extra endpoint bolted on.
     */
    List<Wallet> findByUserIdOrderByCurrency(String userId);

    /**
     * Resolves the wallet a movement belongs to and holds it for the rest of the transaction: the wallet a
     * payment credits, or either wallet of a transfer. The pair is the wallet's natural key and carries a
     * unique constraint, so this returns at most one row, and a movement can never name a wallet whose
     * currency disagrees with its own.
     * <p>
     * The lock is what keeps concurrent credits to one wallet cheap. A balance is read, added to and written
     * back, so two threads reading the same version both write it and one loses — and because the partition
     * key is the transaction reference rather than the wallet, several threads on one wallet is ordinary
     * rather than exotic. Optimistic locking answers that by failing the loser, which here means a rolled-back
     * transaction and a Kafka redelivery for something a few microseconds of waiting resolves. Worse, a busy
     * wallet can lose often enough to exhaust the retry budget and dead-letter a payment that was confirmed.
     * <p>
     * Code that locks more than one wallet in a transaction must lock them in ascending
     * {@code (user_id, currency)}, which for wallets of one currency means ascending user id. Otherwise a
     * transfer from A to B and one from B to A each take their first lock and wait on the other's, a cycle
     * Postgres breaks only by aborting one of them as a deadlock. The order is known from the request, so no
     * read is needed to find it. Ascending wallet id would give a total order too, but an id is known only
     * after the row is read. The next rule forbids that read in the same transaction, and one in a separate
     * transaction costs a round trip for an order the user ids already give.
     * <p>
     * Nothing may load a wallet without a lock earlier in the same transaction. The persistence context
     * would then already hold that wallet, and this query would lock the row and hand back the managed
     * instance. Hibernate compares that instance's version with the locked row's and throws
     * {@code StaleObjectStateException} if another writer committed in between, which fails the transaction
     * as a version conflict (a 503 on a transfer). A read before the lock thus turns ordinary waiting into
     * failed requests. Only a writer that changed a balance without bumping the version could leave a stale
     * balance to judge.
     * <p>
     * {@code @Version} stays on the entity as a backstop for any path that does not take this lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId and w.currency = :currency")
    Optional<Wallet> lockByUserIdAndCurrency(@Param("userId") String userId, @Param("currency") String currency);
}
