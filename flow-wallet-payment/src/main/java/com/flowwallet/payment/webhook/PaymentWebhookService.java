package com.flowwallet.payment.webhook;

import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.WebhookResult;
import com.flowwallet.payment.transaction.PaymentTransactionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {
    /**
     * The reason a PaymentFailedEvent carries for a failure a webhook reported. Consumers do not branch on it.
     */
    static final String FAILURE_REASON = "Payment failed via webhook";

    private final PaymentTransactionHandler transactionHandler;
    private final PaymentProviderFactory factory;

    public void processWebhook(String providerName, String payload, Map<String, String> headers) {
        PaymentProviderStrategy strategy = factory.getStrategy(providerName);

        WebhookResult result = strategy.handleWebhook(payload, headers);

        switch (result.eventType()) {
            case PAYMENT_SUCCESS -> transactionHandler.handleSuccess(result);
            case PAYMENT_FAILURE -> transactionHandler.handleFailure(result, FAILURE_REASON);
            case UNKNOWN -> log.debug("Ignoring unknown webhook event: {}", result);
        }
    }
}
