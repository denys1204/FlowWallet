package com.flowwallet.payment.payout;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One withdrawal's payout under its reference, a namespace of its own apart from {@code payment_transactions}. It
 * holds the terms, the Stripe ids, the outcome and the bookkeeping of the executor's steps.
 * <p>
 * Every mutator applies one transition of ADR 0035's table, as ADR 0037 extends it, and returns whether the status
 * changed; a call from any other status changes nothing, and the caller writes the outbox row only when it did. Every
 * transition clears {@code claimedUntil}, and every move into {@code ACCEPTED}, {@code TRANSFERRED} or
 * {@code REVERSING} makes the row due at once with its attempts counted from zero.
 * <p>
 * {@code claimedUntil} and the {@code *AttemptedAt} columns are also written by the executor's conditional claim,
 * which raises {@code version}, and {@code lastReconciledAt} only by the reconciler's, so a writer loads the row with
 * its lock before it saves. A step's {@code *AttemptedAt} keeps the time of its first claim: an executor that finds
 * an earlier time than its own claim's looks for what Stripe may already hold before it creates anything.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md and
 * docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "payouts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout {
    private static final Set<PayoutStatus> ATTENTION_FROM = EnumSet.of(
            PayoutStatus.ACCEPTED,
            PayoutStatus.TRANSFERRED,
            PayoutStatus.REVERSING
    );

    /**
     * The width of {@code attention_detail}: a longer detail is cut to it.
     */
    static final int ATTENTION_DETAIL_LENGTH = 255;

    private static final Set<PayoutStatus> REVERSIBLE = EnumSet.of(
            PayoutStatus.TRANSFERRED,
            PayoutStatus.PAYOUT_CREATED,
            PayoutStatus.PAID
    );

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payouts_seq_gen")
    @SequenceGenerator(name = "payouts_seq_gen", sequenceName = "payouts_seq", allocationSize = 50)
    private Long id;

    @Column(name = "transaction_reference", nullable = false, unique = true, length = 64)
    private String transactionReference;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "stripe_account_id", length = 128)
    private String stripeAccountId;

    @Column(name = "stripe_transfer_id", unique = true, length = 128)
    private String stripeTransferId;

    @Column(name = "stripe_payout_id", unique = true, length = 128)
    private String stripePayoutId;

    @Column(name = "stripe_reversal_id", length = 128)
    private String stripeReversalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayoutStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 32)
    private PayoutReason reasonCode;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "key_gen", nullable = false)
    private int keyGen;

    @Column(name = "transfer_attempted_at")
    private Instant transferAttemptedAt;

    @Column(name = "payout_attempted_at")
    private Instant payoutAttemptedAt;

    @Column(name = "reversal_attempted_at")
    private Instant reversalAttemptedAt;

    @Column(name = "transferred_at")
    private Instant transferredAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_until")
    private Instant claimedUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "attention_from", length = 20)
    private PayoutStatus attentionFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "attention_cause", length = 20)
    private AttentionCause attentionCause;

    @Column(name = "attention_detail", length = ATTENTION_DETAIL_LENGTH)
    private String attentionDetail;

    /**
     * When the reconciler last asked Stripe about this payout. Only its conditional update writes the column, so a
     * save of a copy read earlier never moves it back.
     * See docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
     */
    @Column(name = "last_reconciled_at", insertable = false, updatable = false)
    private Instant lastReconciledAt;

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
     * A command accepted under its reference, due for its transfer at once.
     */
    public static Payout accepted(
            String transactionReference,
            String userId,
            BigDecimal amount,
            String currency,
            Instant now
    ) {
        return Payout.builder()
                .transactionReference(transactionReference)
                .userId(userId)
                .amount(amount)
                .currency(currency)
                .status(PayoutStatus.ACCEPTED)
                .nextAttemptAt(now)
                .build();
    }

    /**
     * {@code ACCEPTED} to {@code TRANSFERRED}: the transfer to the user's connected account exists.
     */
    public boolean recordTransfer(String stripeAccountId, String stripeTransferId, Instant now) {
        if (status != PayoutStatus.ACCEPTED) {
            return false;
        }
        this.stripeAccountId = Objects.requireNonNull(stripeAccountId, "stripeAccountId");
        this.stripeTransferId = Objects.requireNonNull(stripeTransferId, "stripeTransferId");
        this.transferredAt = now;
        moveTo(PayoutStatus.TRANSFERRED, now);
        return true;
    }

    /**
     * {@code ACCEPTED} to {@code RETURNED}: no transfer was made, so the money goes back without a reversal.
     */
    public boolean returnBeforeTransfer(PayoutReason reason, Instant now) {
        if (status != PayoutStatus.ACCEPTED) {
            return false;
        }
        this.reasonCode = Objects.requireNonNull(reason, "reason");
        moveTo(PayoutStatus.RETURNED, now);
        return true;
    }

    /**
     * {@code TRANSFERRED} to {@code PAYOUT_CREATED}: the payout to the bank exists and now waits for Stripe.
     */
    public boolean recordPayout(String stripePayoutId, Instant now) {
        if (status != PayoutStatus.TRANSFERRED) {
            return false;
        }
        this.stripePayoutId = Objects.requireNonNull(stripePayoutId, "stripePayoutId");
        moveTo(PayoutStatus.PAYOUT_CREATED, now);
        return true;
    }

    /**
     * {@code PAYOUT_CREATED} to {@code PAID}. Not final: a paid payout can still fail, which takes it to
     * {@code REVERSING}.
     */
    public boolean markPaid(Instant now) {
        if (status != PayoutStatus.PAYOUT_CREATED) {
            return false;
        }
        this.paidAt = now;
        moveTo(PayoutStatus.PAID, now);
        return true;
    }

    /**
     * {@code TRANSFERRED}, {@code PAYOUT_CREATED} or {@code PAID} to {@code REVERSING}: the money is certain to come
     * back, and the transfer is reversed next.
     *
     * @param failureCode Stripe's failure code for the payout, or null when Stripe gave none
     */
    public boolean startReversal(PayoutReason reason, String failureCode, Instant now) {
        if (!REVERSIBLE.contains(status)) {
            return false;
        }
        this.reasonCode = Objects.requireNonNull(reason, "reason");
        this.failureCode = failureCode;
        moveTo(PayoutStatus.REVERSING, now);
        return true;
    }

    /**
     * {@code REVERSING} to {@code RETURNED}: the transfer is reversed and the money is back on the platform.
     */
    public boolean recordReversal(String stripeReversalId, Instant now) {
        if (status != PayoutStatus.REVERSING) {
            return false;
        }
        this.stripeReversalId = Objects.requireNonNull(stripeReversalId, "stripeReversalId");
        moveTo(PayoutStatus.RETURNED, now);
        return true;
    }

    /**
     * {@code ACCEPTED}, {@code TRANSFERRED} or {@code REVERSING} to {@code NEEDS_ATTENTION}, remembering the step for
     * {@link #requeue}, and the cause and detail for the operator. The detail is cut to its column.
     */
    public boolean needAttention(AttentionCause cause, @Nullable String detail, Instant now) {
        Objects.requireNonNull(cause, "cause");
        if (!ATTENTION_FROM.contains(status)) {
            return false;
        }
        this.attentionFrom = status;
        this.attentionCause = cause;
        this.attentionDetail = cut(detail);
        moveTo(PayoutStatus.NEEDS_ATTENTION, now);
        return true;
    }

    /**
     * {@code NEEDS_ATTENTION} back to the step it came from, due at once with the step's wait started again. The key
     * generation and the step's {@code *AttemptedAt} stay, so the step looks for what Stripe holds before it creates.
     */
    public boolean requeue(Instant now) {
        if (status != PayoutStatus.NEEDS_ATTENTION) {
            return false;
        }
        PayoutStatus step = attentionFrom;
        leaveAttention();
        moveTo(step, now);
        return true;
    }

    /**
     * {@code NEEDS_ATTENTION} to {@code RETURNED} by an operator, without a reversal. Never from {@code TRANSFERRED}:
     * the transfer sits in the connected account and a payout of it may be live, so the bank and the wallet could
     * both be paid; a reversal can still bring it back. The caller checks Stripe first for the other steps.
     */
    public boolean operatorReturn(Instant now) {
        if (status != PayoutStatus.NEEDS_ATTENTION || attentionFrom == PayoutStatus.TRANSFERRED) {
            return false;
        }
        leaveAttention();
        this.reasonCode = PayoutReason.OPERATOR_RETURNED;
        moveTo(PayoutStatus.RETURNED, now);
        return true;
    }

    /**
     * {@code NEEDS_ATTENTION} from {@code TRANSFERRED} to {@code REVERSING} by an operator, once Stripe shows no payout
     * of the transfer that may reach the bank. The executor's reversal step lists the payouts again before it
     * reverses, and the wallet learns of it as of any payout that failed.
     * See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
     */
    public boolean operatorReverse(Instant now) {
        if (status != PayoutStatus.NEEDS_ATTENTION || attentionFrom != PayoutStatus.TRANSFERRED) {
            return false;
        }
        leaveAttention();
        this.reasonCode = PayoutReason.PAYOUT_FAILED;
        this.failureCode = null;
        moveTo(PayoutStatus.REVERSING, now);
        return true;
    }

    /**
     * A try that left the payout in its step: counts the attempt, releases the claim and waits until
     * {@code nextAttemptAt}.
     */
    public void deferAttempt(Instant nextAttemptAt) {
        this.attempts++;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.claimedUntil = null;
    }

    /**
     * Whether a claim before this one marked the current step attempted. The claim's update keeps the first claim's
     * time in the step's {@code *AttemptedAt}, so any other time there means an earlier try whose outcome may never
     * have been recorded, and the step looks for what Stripe holds before it creates anything.
     */
    public boolean attemptedBefore(Instant claimedAt) {
        Instant first = switch (status) {
            case ACCEPTED -> transferAttemptedAt;
            case TRANSFERRED -> payoutAttemptedAt;
            case REVERSING -> reversalAttemptedAt;
            default -> throw new IllegalStateException("Payout " + transactionReference + " is in no executor step");
        };
        return first != null && !first.equals(claimedAt);
    }

    /**
     * Gives the claim up without counting an attempt, for a try whose calls can have created nothing at Stripe.
     */
    public void releaseClaim() {
        this.claimedUntil = null;
    }

    /**
     * Gives the claim up without counting an attempt and waits until {@code nextAttemptAt}, for a try cut short by a
     * shutdown whose call may still be running at Stripe.
     */
    public void postpone(Instant nextAttemptAt) {
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.claimedUntil = null;
    }

    /**
     * Moves the step to a fresh idempotency key after a definite refusal it retries, because Stripe answers a retry
     * under the same key with the stored refusal.
     */
    public void raiseKeyGen() {
        this.keyGen++;
    }

    /**
     * A detail cut to its column, never through a surrogate pair, which the driver would store as '?'.
     */
    private static @Nullable String cut(@Nullable String detail) {
        if (detail == null || detail.length() <= ATTENTION_DETAIL_LENGTH) {
            return detail;
        }
        int end = Character.isHighSurrogate(detail.charAt(ATTENTION_DETAIL_LENGTH - 1))
                ? ATTENTION_DETAIL_LENGTH - 1
                : ATTENTION_DETAIL_LENGTH;
        return detail.substring(0, end);
    }

    private void leaveAttention() {
        this.attentionFrom = null;
        this.attentionCause = null;
        this.attentionDetail = null;
    }

    private void moveTo(PayoutStatus target, Instant now) {
        this.status = target;
        this.claimedUntil = null;
        if (target.isExecutorStep()) {
            this.nextAttemptAt = now;
            this.attempts = 0;
        }
    }
}
