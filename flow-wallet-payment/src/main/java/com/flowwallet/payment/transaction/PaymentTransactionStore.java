package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Owns the short, DB-only transactions of the payment-creation flow.
 * <p>
 * Splitting these out lets {@link PaymentService} invoke the payment provider (a blocking network call)
 * OUTSIDE any transaction, so a DB connection is never held across the round-trip. It is a separate bean
 * on purpose: Spring's transaction proxy is bypassed on self-invocation, so these {@code @Transactional}
 * methods must be called across a bean boundary to take effect.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTransactionStore {
    private final PaymentTransactionRepository repository;

    /**
     * Returns an existing transaction for an idempotent retry by the SAME owner. A reference owned by a
     * different user is a conflict — never expose another user's provider metadata / client secret.
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
     * Persists a new PENDING transaction. A unique-constraint violation means a concurrent request won the
     * race for this reference; surface it as 409 instead of a 500 (the client's retry then hits findOwnedBy).
     * <p>
     * Treating every integrity violation as that race is only honest because every other constraint on the
     * row is already guaranteed by the time we get here: the reference and provider name are length-bounded
     * by the request, the provider name is further narrowed by the factory before any row is written, the
     * currency is three characters by validation, the amount's scale is checked by the provider, the user id
     * is bounded by the resolver, and the two provider id columns are null at this point. Add a constraint
     * that is not pre-checked and this catch will start reporting it as a duplicate reference.
     * <p>
     * The rethrow happens immediately and issues no further statement, which matters: in Postgres a
     * constraint violation aborts the transaction, so anything attempted afterwards on this connection
     * fails with a message about the aborted transaction rather than the real cause.
     */
    @Transactional
    public PaymentTransaction reserve(CreatePaymentIntentRequest request, String userId) {
        try {
            return repository.saveAndFlush(PaymentTransaction.create(request, userId));
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent creation detected for transaction reference: {}", request.transactionReference());
            throw DuplicateTransactionReferenceException.forReference(request.transactionReference());
        }
    }

    /**
     * Records the provider's initiation result (provider transaction id + metadata) on a reserved transaction.
     */
    @Transactional
    public PaymentTransaction recordInitiation(Long id, String providerTransactionId, Map<String, Object> providerMetadata) {
        PaymentTransaction transaction = repository.findById(id).orElseThrow(
                () -> new TransactionNotFoundException("Transaction not found: " + id)
        );

        transaction.markAsInitiated(providerTransactionId, providerMetadata);
        return repository.save(transaction);
    }
}
