package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
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
     * Orchestrates payment initiation. Deliberately NOT {@code @Transactional}: the provider call is a
     * blocking network round-trip, so it must not run inside a DB transaction. The DB writes happen in
     * {@link PaymentTransactionStore}'s short transactions before and after the provider call.
     */
    public PaymentIntentResponse initiatePayment(CreatePaymentIntentRequest request, String userId) {
        log.info("Initiating payment for user {} with amount {} {}", userId, request.amount(), request.currency());

        Optional<PaymentTransaction> existing = store.findOwnedBy(request.transactionReference(), userId);
        if (existing.isPresent()) {
            log.info("Returning existing payment transaction for reference: {}", request.transactionReference());
            return mapper.toResponse(existing.get());
        }

        // Resolve the provider first so an unknown provider fails fast, before any row is written.
        PaymentProviderStrategy strategy = factory.getStrategy(request.providerName());

        PaymentTransaction reserved = store.reserve(request, userId);

        // Provider (network) call runs OUTSIDE any transaction — no DB connection is held across it.
        PaymentInitiationResult result = strategy.initiatePayment(mapper.toRequestContext(reserved));

        PaymentTransaction initiated = store.recordInitiation(
                reserved.getId(),
                result.providerTransactionId(),
                result.providerData()
        );

        return mapper.toResponse(initiated);
    }
}
