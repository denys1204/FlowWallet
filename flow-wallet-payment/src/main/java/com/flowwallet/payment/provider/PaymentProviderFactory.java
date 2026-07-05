package com.flowwallet.payment.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentProviderFactory {
    private final List<PaymentProviderStrategy> strategies;

    public PaymentProviderStrategy getStrategy(String providerName) {
        PaymentProvider provider = resolve(providerName);
        return strategies.stream()
            .filter(strategy -> strategy.supports(provider))
            .findFirst()
            .orElseThrow(() -> new UnsupportedPaymentProviderException("No strategy found for provider: " + provider));
    }

    private PaymentProvider resolve(String providerName) {
        try {
            return PaymentProvider.valueOf(providerName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedPaymentProviderException("Unsupported payment provider: " + providerName);
        }
    }
}
