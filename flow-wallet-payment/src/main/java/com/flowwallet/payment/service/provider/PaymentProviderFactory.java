package com.flowwallet.payment.service.provider;

import lombok.RequiredArgsConstructor;
import com.flowwallet.payment.exception.UnsupportedPaymentProviderException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentProviderFactory {
    private final List<PaymentProviderStrategy> strategies;

    public PaymentProviderStrategy getStrategy(PaymentProvider provider) {
        return strategies.stream()
            .filter(strategy -> strategy.supports(provider))
            .findFirst()
            .orElseThrow(() -> new UnsupportedPaymentProviderException("No strategy found for provider: " + provider));
    }
}
