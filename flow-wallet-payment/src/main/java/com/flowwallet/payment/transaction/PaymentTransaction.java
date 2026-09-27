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
    @SequenceGenerator(
            name = "payment_transactions_seq_gen",
            sequenceName = "payment_transactions_seq",
            allocationSize = 50
    )
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
     * SUCCESS is terminal, and a FAILED payment can still be promoted because Stripe can retry the same
     * PaymentIntent. See docs/adr/0009-payment-event-contract.md.
     *
     * @return {@code true} only on a state change, which is when a PaymentCompletedEvent is due
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
     * Only a PENDING payment can fail, so a failure after a success, or a second failure, publishes nothing.
     *
     * @return {@code true} only on a state change, which is when a PaymentFailedEvent is due
     */
    public boolean markAsFailed(String providerEventId) {
        if (this.status != TransactionStatus.PENDING) {
            return false;
        }
        this.status = TransactionStatus.FAILED;
        this.providerEventId = providerEventId;
        return true;
    }

    /**
     * Only SUCCESS counts: after a declined card the provider's intent is still usable, so a retry under the same
     * reference gets it back. See docs/adr/0005-client-supplied-idempotency-keys.md.
     */
    public boolean isSettled() {
        return status == TransactionStatus.SUCCESS;
    }

    /**
     * False until the provider's answer is recorded: the call never ran, failed, or was lost before
     * {@code recordInitiation}. A retry calls the provider again on this row.
     * See docs/adr/0013-deposit-initiation.md.
     */
    public boolean isInitiated() {
        return providerTransactionId != null;
    }

    public void markAsInitiated(String providerTransactionId, java.util.Map<String, Object> providerMetadata) {
        this.providerTransactionId = providerTransactionId;
        this.providerMetadata = providerMetadata;
    }

    /**
     * Compares a retry's terms with the stored ones: the amount by {@code compareTo}, so 50.00 matches a stored
     * 50.0000, and currency and provider name upper-cased, as {@link #create} stores them.
     * See docs/adr/0005-client-supplied-idempotency-keys.md.
     *
     * @return the names of the terms that differ, fit to show the caller, or empty if none do
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
