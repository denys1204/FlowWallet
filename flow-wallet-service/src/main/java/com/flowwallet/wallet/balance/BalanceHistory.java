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
 * {@code transactionReference} is NOT NULL and unique together with {@code type}, and that pairing is the
 * barrier that makes a credit happen at most once. Both halves matter: Postgres treats NULLs as distinct under
 * a unique index, so a nullable column would leave the barrier silently inert for exactly the malformed events
 * it exists to stop.
 * <p>
 * Since migration 005 the key includes the type, so a reference may own one movement of each type rather than
 * one movement in all. A payment can still be credited only once, because a reference owns at most one
 * {@code DEPOSIT}, while the two legs of a transfer can share a reference. The same key lets a reference start
 * at most one transfer: two sender wallets racing on one key cannot both write its {@code TRANSFER_OUT}. A
 * lookup by reference must therefore name the type it means.
 * <p>
 * A movement carries {@code balanceBefore} and {@code balanceAfter} so the ledger can be replayed and
 * reconciled against {@link Wallet#getBalance()} without recomputing history.
 * <p>
 * {@code eventId} is nullable on purpose. When the barrier refuses a second credit, it separates the routine
 * case — the same event delivered twice, which at-least-once delivery guarantees will happen — from a producer
 * contract violation, where two different events claim one transaction reference. Without it both look
 * identical, and one of them is a real payment being dropped. It is null on both legs of a transfer, which
 * never passes through {@code payment.events}.
 * <p>
 * {@code counterpartyUserId} names the user on the other side of a transfer: the recipient on
 * {@code TRANSFER_OUT}, the sender on {@code TRANSFER_IN}. Without it a credit would arrive from an invisible
 * source that the recipient could neither recognise nor dispute. It is a user id rather than a wallet id
 * because wallet ids appear in no API, and both legs share one currency, so the user id and this row's own
 * wallet name the other wallet exactly. A CHECK makes it present on the transfer types and absent on every
 * other, so a transfer leg without a counterparty cannot be stored.
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
     * Records a credit. {@code balanceBefore} is the value {@link Wallet#credit(BigDecimal)} returned, so the
     * two sides of the movement come from one read rather than two.
     */
    public static BalanceHistory deposit(
            Wallet wallet,
            String transactionReference,
            String eventId,
            BigDecimal amount, BigDecimal balanceBefore
    ) {
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

    /**
     * Records the sender's leg of a transfer. {@code balanceBefore} is the value {@link Wallet#debit(BigDecimal)}
     * returned, and the amount is positive, as on every movement: the type says the money left.
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
     * user, for the same amount. An Idempotency-Key that does not bind the terms is not idempotency. Without
     * this, a client that corrected the amount or the recipient and retried under the old key would be handed
     * the original transfer as a success.
     * <p>
     * The sender is compared by wallet rather than by user. The wallet fixes the currency too, so one key used
     * from a caller's USD wallet and then from its EUR wallet names two different transfers, and the second is
     * a conflict. The recipient needs no currency, because both legs of a transfer share the sender's.
     * <p>
     * The amount is compared by value, not by {@code equals}, as Payment Service's
     * {@code PaymentTransaction.differencesFrom} does. A stored amount comes back from {@code NUMERIC(19,4)}
     * as 25.0000. Under {@code equals}, a retry that sent 25.00 would be judged a different transfer and sent
     * to a new key unless every caller had rescaled first, and the answer should not rest on that.
     */
    public boolean isRepeatOf(Long senderWalletId, String recipientUserId, BigDecimal amount) {
        return type == TransactionType.TRANSFER_OUT
                && walletId.equals(senderWalletId)
                && recipientUserId.equals(counterpartyUserId)
                && this.amount.compareTo(amount) == 0;
    }
}
