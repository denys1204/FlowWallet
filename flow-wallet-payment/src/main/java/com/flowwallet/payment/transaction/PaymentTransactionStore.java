package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.provider.PaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * The short, database-only transactions of payment creation, in a bean of their own so that
 * {@link PaymentService} reaches them through the transaction proxy and holds no connection during the provider
 * call. See docs/adr/0006-short-transactions-across-bean-boundaries.md.
 */
@Slf4j
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
     * Reports any integrity violation as the concurrent-creation race (409). That is honest only because every
     * other constraint on the row is checked before this point, so a constraint added without a pre-check is
     * reported as a duplicate reference. The rethrow issues no further statement on the aborted transaction.
     * See docs/adr/0007-unique-constraints-decide.md.
     */
    @Transactional
    public PaymentTransaction reserve(CreatePaymentIntentRequest request, String userId, PaymentProvider provider) {
        try {
            return repository.saveAndFlush(PaymentTransaction.create(request, userId, provider));
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent creation detected for transaction reference: {}", request.transactionReference());
            throw DuplicateTransactionReferenceException.forReference(request.transactionReference());
        }
    }

    @Transactional
    public PaymentTransaction recordInitiation(Long id, String providerTransactionId, Map<String, Object> providerMetadata) {
        PaymentTransaction transaction = repository.findById(id).orElseThrow(
                () -> new TransactionNotFoundException("Transaction not found: " + id)
        );

        transaction.markAsInitiated(providerTransactionId, providerMetadata);
        return repository.save(transaction);
    }
}
