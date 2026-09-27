package com.flowwallet.wallet.balance;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A user's balance in a single currency. {@code (user_id, currency)} is unique and is the wallet's address; the
 * currency never changes and nothing converts between currencies.
 * See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 * <p>
 * Every balance writer locks the row first through {@link WalletRepository#lockByUserIdAndCurrency}, and
 * {@code @Version} is the backstop for a path that does not. A balance is never negative: {@link #debit(BigDecimal)}
 * refuses an overdraft and {@code wallets_balance_not_negative} holds the rule for any writer that skips it.
 * See docs/adr/0011-wallet-row-locking.md and docs/adr/0012-balances-and-append-only-ledger.md.
 * <p>
 * {@code lastEntryNo} is the entry number of the wallet's newest ledger row. {@link #credit(BigDecimal)} and
 * {@link #debit(BigDecimal)} advance it under the row lock, so the next entry number is taken in commit order
 * whichever instance writes it. See docs/adr/0021-per-wallet-ledger-entry-numbers.md.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "wallets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "wallets_seq_gen")
    @SequenceGenerator(name = "wallets_seq_gen", sequenceName = "wallets_seq", allocationSize = 50)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "last_entry_no", nullable = false)
    private long lastEntryNo;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Opens an empty wallet. The currency is upper-cased here so that the uniqueness of
     * {@code (user_id, currency)} cannot be defeated by casing.
     */
    public static Wallet open(String userId, String currency) {
        return Wallet.builder()
                .userId(userId)
                .balance(BigDecimal.ZERO)
                .currency(currency.toUpperCase())
                .build();
    }

    /**
     * Credits the wallet and returns the balance as it stood beforehand, so the caller can record both sides of
     * the movement without reading the balance twice. It also takes the next entry number, which the ledger row
     * recording this credit carries.
     *
     * @param amount strictly positive amount in major units; the caller validates this before a transaction opens
     * @return the balance before the credit
     */
    public BigDecimal credit(BigDecimal amount) {
        BigDecimal balanceBefore = balance;
        balance = balance.add(amount);
        lastEntryNo++;
        return balanceBefore;
    }

    /**
     * Debits the wallet and returns the balance as it stood beforehand, mirroring {@link #credit(BigDecimal)}.
     * <p>
     * The caller must hold the row lock, so that the balance judged here is the one written back. The overdraft
     * is refused here with a 422 because {@code wallets_balance_not_negative} fires only at the flush, in an
     * aborted transaction that can no longer tell the caller why. Both checks run before the balance is touched,
     * so a refusal leaves the entity as it was, entry number included. Draining the wallet to exactly zero is
     * allowed.
     * See docs/adr/0012-balances-and-append-only-ledger.md.
     *
     * @param amount strictly positive amount in major units; the caller validates this before a transaction opens
     * @return the balance before the debit
     * @throws IllegalArgumentException   if the amount is zero or negative, which would turn the debit into a
     *                                    credit
     * @throws InsufficientFundsException if the balance is below the amount
     */
    public BigDecimal debit(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A debit must be a positive amount, got " + amount);
        }
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(currency);
        }
        BigDecimal balanceBefore = balance;
        balance = balance.subtract(amount);
        lastEntryNo++;
        return balanceBefore;
    }
}
