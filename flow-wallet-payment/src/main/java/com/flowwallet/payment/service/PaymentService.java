package com.flowwallet.payment.service;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.dto.PaymentIntentResponse;
import com.flowwallet.payment.entity.PaymentTransaction;
import com.flowwallet.payment.repository.PaymentTransactionRepository;
import com.flowwallet.payment.service.provider.PaymentProvider;
import com.flowwallet.payment.service.provider.PaymentProviderFactory;
import com.flowwallet.payment.service.provider.PaymentProviderStrategy;
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

    @Transactional
    public PaymentIntentResponse initiatePayment(CreatePaymentIntentRequest request, String userId) {
        log.info("Initiating payment for user {} with amount {} {}", userId, request.amount(), request.currency());

        PaymentProvider provider = PaymentProvider.valueOf(request.providerName().toUpperCase());
        PaymentProviderStrategy strategy = factory.getStrategy(provider);

        PaymentTransaction transaction = PaymentTransaction.create(request, provider, userId);

        // Delegate to provider-specific strategy
        String clientSecret = strategy.initiatePayment(transaction);

        // Save transaction with PENDING status
        repository.save(transaction);

        return new PaymentIntentResponse(
                clientSecret,
                transaction.getProviderTransactionId(),
                transaction.getTransactionReference()
        );
    }
}
