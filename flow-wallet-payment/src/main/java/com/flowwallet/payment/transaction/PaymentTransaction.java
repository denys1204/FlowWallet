package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Getter
@Builder
@AllArgsConstructor
@Table(name = "payment_transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_transactions_seq_gen")
    @SequenceGenerator(name = "payment_transactions_seq_gen", sequenceName = "payment_transactions_seq")
    private Long id;

    @Column(name = "transaction_reference", nullable = false, unique = true, length = 64)
    private String transactionReference;

    @Column(name = "provider_name", nullable = false, length = 32)
    private String providerName;

    @Column(name = "provider_transaction_id", unique = true, length = 128)
    private String providerTransactionId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "provider_event_id", unique = true, length = 128)
    private String providerEventId;

    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "provider_metadata", columnDefinition = "jsonb")
    private java.util.Map<String, Object> providerMetadata;

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
     * Settles the transaction as SUCCESS. Idempotent — an already-SUCCESS transaction is left unchanged.
     * A previously FAILED attempt may still be promoted, since Stripe can retry the same PaymentIntent and
     * eventually succeed.
     *
     * @return {@code true} only if this call actually changed the state (i.e. a PaymentCompleted event is due)
     */
    public boolean markAsSuccess(String providerEventId) {
        if (this.status == TransactionStatus.SUCCESS) {
            return false;
        }
        this.status = TransactionStatus.SUCCESS;
        this.providerEventId = providerEventId;
        return true;
    }

    /**
     * Marks the transaction as FAILED. Only a PENDING transaction may fail: SUCCESS is terminal, and an
     * already-FAILED transaction is left unchanged so no duplicate event is emitted.
     *
     * @return {@code true} only if this call actually changed the state (i.e. a PaymentFailed event is due)
     */
    public boolean markAsFailed(String providerEventId) {
        if (this.status != TransactionStatus.PENDING) {
            return false;
        }
        this.status = TransactionStatus.FAILED;
        this.providerEventId = providerEventId;
        return true;
    }

    public void markAsInitiated(String providerTransactionId, java.util.Map<String, Object> providerMetadata) {
        this.providerTransactionId = providerTransactionId;
        this.providerMetadata = providerMetadata;
    }

    /**
     * Whether this transaction was created from terms equal to the given request.
     * <p>
     * An idempotency key that does not bind the payload is not idempotency. Without this, a client that
     * posts a reference, notices a mistake and re-posts the same reference with a corrected amount receives
     * the original payment's client secret and a 200, and charges the original amount believing it corrected
     * it. Stripe's own idempotency would have refused that, but the local short-circuit means Stripe is
     * never reached.
     * <p>
     * Compared on the canonical forms, which is why {@code providerName} is upper-cased here as it is in
     * {@link #create} — otherwise a byte-identical retry sending {@code "stripe"} would be reported as a
     * conflict. Amount is compared by value, not by {@code equals}, so 50.00 and a stored 50.0000 agree.
     *
     * @return the terms that differ, in a form fit to show the caller, or empty if none do
     */
    public Optional<String> differencesFrom(CreatePaymentIntentRequest request) {
        List<String> differences = new ArrayList<>();
        if (amount.compareTo(request.amount()) != 0) {
            differences.add("amount");
        }
        if (!currency.equals(request.currency().toUpperCase())) {
            differences.add("currency");
        }
        if (!providerName.equals(request.providerName().toUpperCase())) {
            differences.add("provider");
        }
        return differences.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", differences));
    }

    public static PaymentTransaction create(
            CreatePaymentIntentRequest request,
            String userId
    ) {
        return PaymentTransaction.builder()
                .transactionReference(request.transactionReference())
                .providerName(request.providerName().toUpperCase())
                .userId(userId)
                .amount(request.amount())
                .currency(request.currency().toUpperCase())
                .status(TransactionStatus.PENDING)
                .build();
    }
}
