package com.flowwallet.payment.provider;

import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class PaymentProviderFactory {
    private final List<PaymentProviderStrategy> strategies;

    public PaymentProviderStrategy getStrategy(String providerName) {
        return getStrategy(resolve(providerName));
    }

    public PaymentProviderStrategy getStrategy(PaymentProvider provider) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(provider))
                .findFirst()
                .orElseThrow(() -> new UnsupportedPaymentProviderException(
                        "No strategy found for provider: " + provider
                ));
    }

    /**
     * Matches the name case-insensitively. {@code Locale.ROOT} keeps the match independent of the host's locale:
     * under a Turkish default, {@code "stripe".toUpperCase()} is {@code "STRİPE"}, which names no provider.
     * <p>
     * The refusal does not quote the name. On the webhook it is a public path segment of any content, and the
     * handler logs every 4xx detail. See docs/adr/0026-problem-details-never-quote-rejected-input.md.
     */
    public PaymentProvider resolve(String providerName) {
        try {
            return PaymentProvider.valueOf(providerName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnsupportedPaymentProviderException("Unsupported payment provider");
        }
    }
}
