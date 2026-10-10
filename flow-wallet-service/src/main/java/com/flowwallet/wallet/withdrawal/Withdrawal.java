package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.balance.Wallet;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One withdrawal request under its {@code Idempotency-Key}, refused or debited. {@code walletId} is a plain id, like
 * {@code BalanceHistory.walletId}, because a {@code @ManyToOne Wallet} would load the wallet without its lock.
 * <p>
 * The reference is unique as {@code withdrawals_reference_key}. A CHECK requires {@code refusalReason} exactly when the
 * status is {@code REFUSED}. The money moves in the ledger, not here: the debit is a {@code WITHDRAWAL} row and a
 * return a {@code WITHDRAWAL_RETURNED} row under the same reference.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "withdrawals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Withdrawal {
    /**
     * Stored in place of a reason code that is not 1 to 32 capitals and underscores.
     */
    static final String OTHER_REASON = "OTHER";

    private static final Pattern REASON_CODE = Pattern.compile("[A-Z_]{1,32}");

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "withdrawals_seq_gen")
    @SequenceGenerator(name = "withdrawals_seq_gen", sequenceName = "withdrawals_seq", allocationSize = 50)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "transaction_reference", nullable = false, length = 64)
    private String transactionReference;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WithdrawalStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "refusal_reason", length = 32)
    private WithdrawalRefusalReason refusalReason;

    @Column(name = "return_reason", length = 32)
    private String returnReason;

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
     * A withdrawal the wallet has just debited, written in the same transaction as its {@code WITHDRAWAL} ledger row.
     */
    public static Withdrawal pending(Wallet wallet, String transactionReference, BigDecimal amount) {
        return Withdrawal.builder()
                .walletId(wallet.getId())
                .transactionReference(transactionReference)
                .amount(amount)
                .status(WithdrawalStatus.PENDING)
                .build();
    }

    /**
     * A withdrawal turned down before any debit. The row keeps the key spent, so a retry gets the same refusal.
     */
    public static Withdrawal refused(
            Wallet wallet,
            String transactionReference,
            BigDecimal amount,
            WithdrawalRefusalReason reason
    ) {
        return Withdrawal.builder()
                .walletId(wallet.getId())
                .transactionReference(transactionReference)
                .amount(amount)
                .status(WithdrawalStatus.REFUSED)
                .refusalReason(Objects.requireNonNull(reason, "reason"))
                .build();
    }

    /**
     * Whether this withdrawal is for this wallet and amount: a request that is a replay of it, or a payout event
     * about it. The amount is compared with {@code compareTo}: a stored amount comes back from {@code NUMERIC(19,4)}
     * as 25.0000. See docs/adr/0005-client-supplied-idempotency-keys.md.
     */
    public boolean hasTerms(Long walletId, BigDecimal amount) {
        return this.walletId.equals(walletId) && this.amount.compareTo(amount) == 0;
    }

    /**
     * Records that Stripe reported the payout paid.
     *
     * @return whether the status rose; a withdrawal already ranked higher keeps its status
     */
    public boolean markPaid() {
        return moveUpTo(WithdrawalStatus.PAID);
    }

    /**
     * Records that the payout failed or could not be made and the money is on its way back.
     *
     * @return whether the status rose; the reason is recorded only then, so the stored reason is that of the event
     *         that last raised the status
     */
    public boolean markReturnPending(String reasonCode) {
        return moveUpWithReason(WithdrawalStatus.RETURN_PENDING, reasonCode);
    }

    /**
     * Records that the money is back. The credit itself is the {@code WITHDRAWAL_RETURNED} ledger row, and it does
     * not depend on this result: a second return under another event id still reaches the ledger key and is refused
     * there. See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
     *
     * @return whether the status rose; the reason is recorded only then, replacing the reason of an earlier failure
     */
    public boolean markReturned(String reasonCode) {
        return moveUpWithReason(WithdrawalStatus.RETURNED, reasonCode);
    }

    /**
     * The wallet keeps Payment Service's reason as it came when it looks like a code, and {@code OTHER} for anything
     * else, so the column holds it whatever the producer sends. Nothing branches on it.
     */
    static String storedReasonCode(String reasonCode) {
        return reasonCode != null && REASON_CODE.matcher(reasonCode).matches() ? reasonCode : OTHER_REASON;
    }

    private boolean moveUpTo(WithdrawalStatus target) {
        if (status == WithdrawalStatus.REFUSED) {
            throw new IllegalStateException("Refused withdrawal " + transactionReference + " cannot change status");
        }
        if (!target.outranks(status)) {
            return false;
        }
        status = target;
        return true;
    }

    private boolean moveUpWithReason(WithdrawalStatus target, String reasonCode) {
        if (!moveUpTo(target)) {
            return false;
        }
        returnReason = storedReasonCode(reasonCode);
        return true;
    }
}
