package com.flowwallet.payment.transaction;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentTransactionRepository repository;
    private final PaymentProviderFactory factory;
    private final PaymentEventMapper mapper;

    @Transactional
    public PaymentIntentResponse initiatePayment(CreatePaymentIntentRequest request, String userId) {
        log.info("Initiating payment for user {} with amount {} {}", userId, request.amount(), request.currency());

        Optional<PaymentIntentResponse> existing = findExistingForOwner(request.transactionReference(), userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Resolve the provider first so an unknown provider fails fast, before any row is written.
        PaymentProviderStrategy strategy = factory.getStrategy(request.providerName());

        PaymentTransaction transaction = persistNewTransaction(request, userId);

        PaymentRequestContext context = mapper.toRequestContext(transaction);
        PaymentInitiationResult result = strategy.initiatePayment(context);

        transaction.markAsInitiated(result.providerTransactionId(), result.providerData());

        repository.save(transaction);

        return new PaymentIntentResponse(
                result.providerData(),
                transaction.getProviderTransactionId(),
                transaction.getTransactionReference()
        );
    }

    /**
     * Returns the already-created payment intent for an idempotent retry by the SAME owner. A reference owned
     * by a different user is a conflict — never leak another user's provider metadata (e.g. the Stripe client secret).
     */
    private Optional<PaymentIntentResponse> findExistingForOwner(String transactionReference, String userId) {
        return repository.findByTransactionReference(transactionReference)
                .map(tx -> {
                    if (!tx.getUserId().equals(userId)) {
                        throw DuplicateTransactionReferenceException.forReference(transactionReference);
                    }
                    log.info("Returning existing payment transaction for reference: {}", transactionReference);
                    return new PaymentIntentResponse(
                            tx.getProviderMetadata(),
                            tx.getProviderTransactionId(),
                            tx.getTransactionReference()
                    );
                });
    }

    /**
     * Inserts a new PENDING transaction. A unique-constraint violation means a concurrent request won the race
     * for this reference; surface it as 409 instead of a 500 (the client's retry then hits the idempotent path).
     */
    private PaymentTransaction persistNewTransaction(CreatePaymentIntentRequest request, String userId) {
        try {
            return repository.saveAndFlush(PaymentTransaction.create(request, userId));
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent creation detected for transaction reference: {}", request.transactionReference());
            throw DuplicateTransactionReferenceException.forReference(request.transactionReference());
        }
    }
}
