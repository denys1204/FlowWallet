package com.flowwallet.payment.service;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.dto.PaymentIntentResponse;
import com.flowwallet.payment.entity.PaymentTransaction;
import com.flowwallet.payment.repository.PaymentTransactionRepository;
import com.flowwallet.payment.service.mapper.PaymentEventMapper;
import com.flowwallet.payment.service.provider.PaymentInitiationResult;
import com.flowwallet.payment.service.provider.PaymentProvider;
import com.flowwallet.payment.service.provider.PaymentProviderFactory;
import com.flowwallet.payment.service.provider.PaymentProviderStrategy;
import com.flowwallet.payment.service.provider.PaymentRequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        PaymentProvider provider = factory.resolve(request.providerName());
        PaymentProviderStrategy strategy = factory.getStrategy(provider);

        PaymentTransaction transaction = repository.save(
                PaymentTransaction.create(request, provider, userId)
        );

        PaymentRequestContext context = mapper.toRequestContext(transaction);

        PaymentInitiationResult result = strategy.initiatePayment(context);
        transaction.setProviderTransactionId(result.providerTransactionId());

        // Save transaction with PENDING status
        repository.save(transaction);

        return new PaymentIntentResponse(
                result.clientSecret(),
                transaction.getProviderTransactionId(),
                transaction.getTransactionReference()
        );
    }
}
