package com.flowwallet.wallet.balance;

import com.flowwallet.wallet.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement on a wallet. The table is append-only, so there is no {@code @Version} and no
 * {@code @UpdateTimestamp}.
 * <p>
 * {@code (transactionReference, type)} is unique and the reference is NOT NULL. A reference owns at most one
 * movement of each type, so both legs of a transfer share one and every lookup by reference names its type.
 * {@code counterpartyUserId} is the other user of a transfer, which a CHECK requires on the transfer types and
 * forbids on the rest. {@code eventId} is null on both transfer legs, which never pass through
 * {@code payment.events}. See docs/adr/0012-balances-and-append-only-ledger.md.
 * <p>
 * {@code entryNo} numbers a wallet's movements 1, 2, 3 in commit order and is unique per wallet; it orders the
 * history and is its cursor. The id comes from a pooled sequence and orders nothing. Each factory takes the
 * entry number the wallet's {@code credit} or {@code debit} just advanced, so it must be called right after the
 * mutation it records. See docs/adr/0021-per-wallet-ledger-entry-numbers.md.
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

    @Column(name = "entry_no", nullable = false)
    private Long entryNo;

    @Column(name = "transaction_reference", nullable = false, length = 64)
    private String transactionReference;

    @Column(name = "event_id", length = 128)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "counterparty_user_id", length = 64)
    private String counterpartyUserId;

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
     * Records a credit. {@code balanceBefore} is the value {@link Wallet#credit(BigDecimal)} returned.
     */
    public static BalanceHistory deposit(
            Wallet wallet,
            String transactionReference,
            String eventId,
            BigDecimal amount, BigDecimal balanceBefore
    ) {
        return BalanceHistory.builder()
                .walletId(wallet.getId())
                .entryNo(wallet.getLastEntryNo())
                .transactionReference(transactionReference)
                .eventId(eventId)
                .type(TransactionType.DEPOSIT)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceBefore.add(amount))
                .build();
    }

    /**
     * Records the sender's leg of a transfer. {@code balanceBefore} is the value {@link Wallet#debit(BigDecimal)}
     * returned, and the amount stays positive: the type says the money left.
     *
     * @param recipientUserId the owner of the wallet the money went to, which the sender's history shows
     */
    public static BalanceHistory transferOut(
            Wallet from,
            String transactionReference,
            String recipientUserId,
            BigDecimal amount,
            BigDecimal balanceBefore
    ) {
        return BalanceHistory.builder()
                .walletId(from.getId())
                .entryNo(from.getLastEntryNo())
                .transactionReference(transactionReference)
                .type(TransactionType.TRANSFER_OUT)
                .counterpartyUserId(recipientUserId)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceBefore.subtract(amount))
                .build();
    }

    /**
     * Records the recipient's leg of a transfer, under the same reference as the sender's.
     * {@code balanceBefore} is the value {@link Wallet#credit(BigDecimal)} returned.
     *
     * @param senderUserId the owner of the wallet the money came from, which the recipient's history shows
     */
    public static BalanceHistory transferIn(
            Wallet to,
            String transactionReference,
            String senderUserId,
            BigDecimal amount,
            BigDecimal balanceBefore
    ) {
        return BalanceHistory.builder()
                .walletId(to.getId())
                .entryNo(to.getLastEntryNo())
                .transactionReference(transactionReference)
                .type(TransactionType.TRANSFER_IN)
                .counterpartyUserId(senderUserId)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceBefore.add(amount))
                .build();
    }

    /**
     * Whether this movement is the sending leg of the transfer described: out of the same wallet, to the same
     * user, for the same amount.
     * <p>
     * The sender is compared by wallet, which also fixes the currency. The amount is compared with
     * {@code compareTo}: a stored amount comes back from {@code NUMERIC(19,4)} as 25.0000, and under
     * {@code equals} a retry that sent 25.00 would count as a different transfer.
     * See docs/adr/0005-client-supplied-idempotency-keys.md.
     */
    public boolean isRepeatOf(Long senderWalletId, String recipientUserId, BigDecimal amount) {
        return type == TransactionType.TRANSFER_OUT
                && walletId.equals(senderWalletId)
                && recipientUserId.equals(counterpartyUserId)
                && this.amount.compareTo(amount) == 0;
    }
}
