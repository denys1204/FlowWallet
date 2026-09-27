package com.flowwallet.wallet.deposit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentClientConfigTest {
    @Test
    void thePaymentClientIsBuiltFromBootsAutoConfiguredBuilder() {
        // Guards a return to the static RestClient.builder(), which skips every RestClientCustomizer, among them
        // the one that records client metrics and traces for the call to Payment Service.
        AtomicInteger customizations = new AtomicInteger();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        RestClientAutoConfiguration.class
                ))
                .withBean(RestClientCustomizer.class, () -> builder -> customizations.incrementAndGet())
                .withUserConfiguration(WalletPaymentProperties.class, PaymentClientConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PaymentIntentClient.class);
                    assertThat(customizations).hasValue(1);
                });
    }
}
