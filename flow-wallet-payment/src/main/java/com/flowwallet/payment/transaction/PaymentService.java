package com.flowwallet.payment.transaction;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.dto.PaymentIntentResponse;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        Optional<PaymentTransaction> existingTx = repository.findByTransactionReference(request.transactionReference());
        if (existingTx.isPresent()) {
            PaymentTransaction tx = existingTx.get();
            log.info("Returning existing payment transaction for reference: {}", request.transactionReference());
            return new PaymentIntentResponse(
                    tx.getProviderMetadata(),
                    tx.getProviderTransactionId(),
                    tx.getTransactionReference()
            );
        }

        PaymentProvider provider = factory.resolve(request.providerName());
        PaymentProviderStrategy strategy = factory.getStrategy(provider);

        PaymentTransaction transaction = repository.save(
                PaymentTransaction.create(request, provider, userId)
        );

        PaymentRequestContext context = mapper.toRequestContext(transaction);

        PaymentInitiationResult result = strategy.initiatePayment(context);
        transaction.setProviderTransactionId(result.providerTransactionId());
        transaction.setProviderMetadata(result.providerData());

        // Save transaction with PENDING status and provider metadata
        repository.save(transaction);

        return new PaymentIntentResponse(
                result.providerData(),
                transaction.getProviderTransactionId(),
                transaction.getTransactionReference()
        );
    }
}
