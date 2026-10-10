package com.flowwallet.wallet.deposit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;

/**
 * Builds the Payment Service client for deposits: a declarative HTTP interface over {@code RestClient}, started from
 * Boot's auto-configured builder so that Boot's message converters and client observation apply to the call. The
 * bank account client is built the same way, from {@link #proxyFactory}. See docs/adr/0013-deposit-initiation.md.
 */
@Configuration
@RequiredArgsConstructor
public class PaymentClientConfig {
    @Bean
    PaymentIntentClient paymentIntentClient(RestClient.Builder builder, WalletPaymentProperties properties) {
        return proxyFactory(builder, properties).createClient(PaymentIntentClient.class);
    }

    /**
     * The factory every Payment Service client is created from: Boot's builder with the base URL and both timeouts.
     */
    public static HttpServiceProxyFactory proxyFactory(
            RestClient.Builder builder,
            WalletPaymentProperties properties
    ) {
        // The connect timeout lives on the JDK client and the read timeout on the factory. Either left at its
        // default lets a wedged Payment Service hold a wallet request thread indefinitely.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        // The builder is a prototype bean, so each client gets its own and this configuration leaks into no other.
        RestClient restClient = builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
    }
}
