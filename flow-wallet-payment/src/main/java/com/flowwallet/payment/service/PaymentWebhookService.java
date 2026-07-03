package com.flowwallet.payment.service;

import com.flowwallet.payment.service.provider.PaymentProvider;
import com.flowwallet.payment.service.provider.PaymentProviderFactory;
import com.flowwallet.payment.service.provider.PaymentProviderStrategy;
import com.flowwallet.payment.service.provider.WebhookResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {
    private final PaymentTransactionHandler transactionHandler;
    private final PaymentProviderFactory factory;

    public void processWebhook(String providerName, String payload, Map<String, String> headers) {
        PaymentProvider provider = factory.resolve(providerName);
        PaymentProviderStrategy strategy = factory.getStrategy(provider);

        WebhookResult result = strategy.handleWebhook(payload, headers);

        switch (result.eventType()) {
            case PAYMENT_SUCCESS -> transactionHandler.handleSuccess(result.providerTransactionId(), result.providerEventId());
            case PAYMENT_FAILURE -> transactionHandler.handleFailure(result.providerTransactionId(), result.providerEventId());
            case UNKNOWN -> log.debug("Ignoring unknown webhook event");
        }
    }
}
