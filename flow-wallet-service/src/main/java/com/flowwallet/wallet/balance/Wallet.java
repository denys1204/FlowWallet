package com.flowwallet.wallet.balance;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A user's balance in a single currency.
 * <p>
 * A user may hold several wallets, but only one per currency — enforced by a unique constraint on
 * {@code (user_id, currency)} rather than by a read-then-write check, which two concurrent first payments
 * would race past.
 * <p>
 * The currency is fixed for the wallet's lifetime. Nothing converts between currencies, so a movement whose
 * currency differs from the wallet's is a rejection, never a conversion.
 * <p>
 * {@code @Version} is load-bearing: two payments for one user can be credited concurrently, and the partition
 * key on {@code payment.events} is the transaction reference rather than the wallet id, so concurrent writers
 * on one row are ordinary rather than exotic.
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
     * the movement without reading the balance twice.
     *
     * @param amount strictly positive amount in major units; the caller validates this before a transaction opens
     * @return the balance before the credit
     */
    public BigDecimal credit(BigDecimal amount) {
        BigDecimal balanceBefore = balance;
        balance = balance.add(amount);
        return balanceBefore;
    }
}
