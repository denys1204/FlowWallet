package com.flowwallet.wallet.deposit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds and validates the real class, so a setting that cannot work stops startup instead of failing every deposit
 * behind a green health check.
 */
class WalletPaymentPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(WalletPaymentProperties.class);

    @Test
    void theShippedDefaultsStartAndMatchTheDocumentedOnes() {
        // Guards a default that fails its own checks, which would stop every start with no setting made.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            WalletPaymentProperties properties = context.getBean(WalletPaymentProperties.class);

            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getProviderName()).isEqualTo("STRIPE");
        });
    }

    @ParameterizedTest(name = "{0} fails startup")
    @ValueSource(strings = {
            "wallet.payment.read-timeout=0",
            "wallet.payment.read-timeout=-1s",
            "wallet.payment.connect-timeout=0ms",
            "wallet.payment.provider-name=STRPE",
            "wallet.payment.provider-name=stripe"
    })
    void aSettingThatWouldFailEveryDepositFailsStartup(String setting) {
        // Guards a zero read timeout, which makes the JDK client time out at once so every deposit gets a 502,
        // and a provider Payment Service does not know, which it refuses with a 400 the wallet relays to callers.
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }
}
