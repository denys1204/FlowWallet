package com.flowwallet.wallet.balance;

import com.flowwallet.wallet.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement on a wallet, recorded append-only. Rows are never updated or deleted, so there is no
 * {@code @Version} and no {@code @UpdateTimestamp}.
 * <p>
 * {@code transactionReference} is NOT NULL and unique, and that pairing is the barrier that makes a credit
 * happen at most once. Both halves matter: Postgres treats NULLs as distinct under a unique index, so a
 * nullable column would leave the barrier silently inert for exactly the malformed events it exists to stop.
 * <p>
 * A movement carries {@code balanceBefore} and {@code balanceAfter} so the ledger can be replayed and
 * reconciled against {@link Wallet#getBalance()} without recomputing history.
 * <p>
 * {@code eventId} is nullable on purpose. When the barrier refuses a second credit, it separates the routine
 * case — the same event delivered twice, which at-least-once delivery guarantees will happen — from a producer
 * contract violation, where two different events claim one transaction reference. Without it both look
 * identical, and one of them is a real payment being dropped.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "balance_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BalanceHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "balance_history_seq_gen")
    @SequenceGenerator(name = "balance_history_seq_gen", sequenceName = "balance_history_seq", allocationSize = 50)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "transaction_reference", nullable = false, length = 64)
    private String transactionReference;

    @Column(name = "event_id", length = 128)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Records a credit. {@code balanceBefore} is the value {@link Wallet#credit(BigDecimal)} returned, so the
     * two sides of the movement come from one read rather than two.
     */
    public static BalanceHistory deposit(Wallet wallet, String transactionReference, String eventId,
                                         BigDecimal amount, BigDecimal balanceBefore) {
        return BalanceHistory.builder()
                .walletId(wallet.getId())
                .transactionReference(transactionReference)
                .eventId(eventId)
                .type(TransactionType.DEPOSIT)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceBefore.add(amount))
                .build();
    }
}
