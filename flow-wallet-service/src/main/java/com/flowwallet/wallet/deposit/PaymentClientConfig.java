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
 * Builds the Payment Service client.
 * <p>
 * A declarative HTTP interface backed by {@code RestClient} — the shape Feign gave us, without Feign. That
 * dependency was deleted as unused, and everything needed here already arrives with the web starter, so
 * bringing Spring Cloud back for one call would be a poor trade.
 */
@Configuration
@RequiredArgsConstructor
public class PaymentClientConfig {

    @Bean
    PaymentIntentClient paymentIntentClient(WalletPaymentProperties properties) {
        // Both bounds are set explicitly. The connect timeout lives on the JDK client and the read timeout
        // on the factory, and leaving either at its default means a wedged Payment Service can hold a wallet
        // request thread for as long as it likes.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(PaymentIntentClient.class);
    }
}
