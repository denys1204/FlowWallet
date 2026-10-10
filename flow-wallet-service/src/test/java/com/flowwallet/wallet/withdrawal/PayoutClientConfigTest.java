package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.deposit.WalletPaymentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutClientConfigTest {
    @Test
    void thePreflightClientIsBuiltFromBootsAutoConfiguredBuilder() {
        // Guards a return to the static RestClient.builder(), which skips every RestClientCustomizer, among them
        // the one that records client metrics and traces for the preflight call to Payment Service.
        AtomicInteger customizations = new AtomicInteger();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        RestClientAutoConfiguration.class
                ))
                .withBean(RestClientCustomizer.class, () -> builder -> customizations.incrementAndGet())
                .withUserConfiguration(WalletPaymentProperties.class, PayoutClientConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PayoutClient.class);
                    assertThat(customizations).hasValue(1);
                });
    }
}
