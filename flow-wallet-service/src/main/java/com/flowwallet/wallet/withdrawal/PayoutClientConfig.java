package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.deposit.PaymentClientConfig;
import com.flowwallet.wallet.deposit.WalletPaymentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the preflight client with the deposit client's base URL and timeouts. It lives beside the interface, which
 * stays package-private for the reason the interface gives.
 */
@Configuration
public class PayoutClientConfig {
    @Bean
    PayoutClient payoutClient(RestClient.Builder builder, WalletPaymentProperties properties) {
        return PaymentClientConfig.proxyFactory(builder, properties).createClient(PayoutClient.class);
    }
}
