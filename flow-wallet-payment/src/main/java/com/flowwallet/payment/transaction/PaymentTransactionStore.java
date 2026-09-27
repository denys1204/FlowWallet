package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.provider.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * The short, database-only transactions of payment creation, in a bean of their own so that
 * {@link PaymentService} reaches them through the transaction proxy and holds no connection during the provider
 * call. See docs/adr/0006-short-transactions-across-bean-boundaries.md.
 */
@Component
@RequiredArgsConstructor
public class PaymentTransactionStore {
    private final PaymentTransactionRepository repository;

    /**
     * Another user's reference is a 409 and is never returned, because the row carries that user's client secret.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentTransaction> findOwnedBy(String transactionReference, String userId) {
        return repository.findByTransactionReference(transactionReference).map(tx -> {
            if (!tx.getUserId().equals(userId)) {
                throw DuplicateTransactionReferenceException.forReference(transactionReference);
            }
            return tx;
        });
    }

    /**
     * Catches nothing: a violation rolls this transaction back and reaches {@link PaymentService}, which explains it
     * from a fresh read. See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
     */
    @Transactional
    public PaymentTransaction reserve(CreatePaymentIntentRequest request, String userId, PaymentProvider provider) {
        return repository.saveAndFlush(PaymentTransaction.create(request, userId, provider));
    }

    /**
     * Records the provider's answer once. The row lock makes a concurrent recording of the same answer, from a
     * same-key request that also reached the provider, wait and then find the row initiated, which it returns as it
     * stands instead of failing the version check. The provider's idempotency key makes a different answer
     * impossible, so one is a defect. See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
     */
    @Transactional
    public PaymentTransaction recordInitiation(
            Long id,
            String providerTransactionId,
            Map<String, Object> providerMetadata
    ) {
        PaymentTransaction transaction = repository.lockById(id).orElseThrow(
                () -> new TransactionNotFoundException("Transaction not found: " + id)
        );

        if (transaction.isInitiated()) {
            if (!transaction.getProviderTransactionId().equals(providerTransactionId)) {
                throw new IllegalStateException(
                        "Transaction %s is already initiated with a different provider id"
                                .formatted(transaction.getTransactionReference())
                );
            }
            return transaction;
        }

        transaction.markAsInitiated(providerTransactionId, providerMetadata);
        return repository.save(transaction);
    }
}
