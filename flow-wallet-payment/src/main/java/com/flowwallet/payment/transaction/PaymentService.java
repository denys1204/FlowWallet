package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentProviderFactory factory;
    private final PaymentTransactionStore store;
    private final PaymentEventMapper mapper;

    /**
     * Not {@code @Transactional}: the provider call runs between the store's short transactions, with no
     * connection held. See docs/adr/0006-short-transactions-across-bean-boundaries.md and
     * docs/adr/0013-deposit-initiation.md.
     */
    public PaymentIntentResponse initiatePayment(CreatePaymentIntentRequest request, String userId) {
        log.info("Initiating payment for user {} with amount {} {}", userId, request.amount(), request.currency());

        Optional<PaymentTransaction> existing = store.findOwnedBy(request.transactionReference(), userId);
        if (existing.isPresent()) {
            PaymentTransaction transaction = existing.get();
            transaction.differencesFrom(request).ifPresent(differences -> {
                log.warn("Reference {} reused with a different {}", request.transactionReference(), differences);
                throw DuplicateTransactionReferenceException.forConflictingPayload(
                        request.transactionReference(), differences
                );
            });

            if (transaction.isSettled()) {
                log.warn("Reference {} was already paid; refusing to hand back a spent intent",
                        request.transactionReference());
                throw DuplicateTransactionReferenceException.forSettledReference(request.transactionReference());
            }

            if (transaction.isInitiated()) {
                log.info("Returning existing payment transaction for reference: {}", request.transactionReference());
                return mapper.toResponse(transaction);
            }

            log.warn("Reference {} was reserved but never reached the provider; retrying initiation",
                    request.transactionReference());
        }

        // Factory lookup and validation can refuse, so they run before reserve and a refusal leaves the reference free.
        PaymentProviderStrategy strategy = factory.getStrategy(request.providerName());
        strategy.validateRequest(new PaymentRequestContext(
                request.transactionReference(),
                request.amount(),
                request.currency(),
                userId
        ));

        // A reserved row whose initiation was never recorded is reused. The reference is Stripe's idempotency key,
        // so the repeated call creates no second intent.
        PaymentTransaction reserved = existing.orElseGet(() -> store.reserve(request, userId));

        PaymentInitiationResult result = strategy.initiatePayment(mapper.toRequestContext(reserved));

        PaymentTransaction initiated = store.recordInitiation(
                reserved.getId(),
                result.providerTransactionId(),
                result.providerData()
        );

        return mapper.toResponse(initiated);
    }
}
