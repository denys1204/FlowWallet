package com.flowwallet.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real class against real property values, as {@code PaymentDepositPropertiesTest} does.
 */
class PaymentWebhookPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PaymentWebhookProperties.class);

    @Test
    @DisplayName("falls back to 256KB when nothing is configured")
    void defaultsTo256Kilobytes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PaymentWebhookProperties.class).getMaxPayloadSize())
                    .isEqualTo(DataSize.ofKilobytes(256));
        });
    }

    @Test
    @DisplayName("binds the documented property key with a unit")
    void bindsTheDocumentedKey() {
        runner.withPropertyValues("payment.webhook.max-payload-size=1MB").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PaymentWebhookProperties.class).getMaxPayloadSize())
                    .isEqualTo(DataSize.ofMegabytes(1));
        });
    }

    @ParameterizedTest(name = "{0} fails startup")
    @ValueSource(strings = {"0", "-1KB", "17MB"})
    void aLimitOutsideTheRangeFailsStartup(String size) {
        // Zero would refuse every webhook in silence, and a very large limit brings back the unbounded read.
        runner.withPropertyValues("payment.webhook.max-payload-size=" + size)
                .run(context -> assertThat(context).hasFailed());
    }
}
