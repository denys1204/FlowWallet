package com.flowwallet.payment.transaction;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

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

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

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

    public void markAsSuccess(String providerEventId) {
        this.status = TransactionStatus.SUCCESS;
        this.providerEventId = providerEventId;
    }

    public void markAsFailed(String providerEventId) {
        this.status = TransactionStatus.FAILED;
        this.providerEventId = providerEventId;
    }

    public void markAsInitiated(String providerTransactionId, java.util.Map<String, Object> providerMetadata) {
        this.providerTransactionId = providerTransactionId;
        this.providerMetadata = providerMetadata;
    }

    public static PaymentTransaction create(
            CreatePaymentIntentRequest request,
            String userId
    ) {
        return PaymentTransaction.builder()
                .transactionReference(request.transactionReference())
                .providerName(request.providerName().toUpperCase())
                .walletId(request.walletId())
                .userId(userId)
                .amount(request.amount())
                .currency(request.currency().toUpperCase())
                .status(TransactionStatus.PENDING)
                .build();
    }
}
