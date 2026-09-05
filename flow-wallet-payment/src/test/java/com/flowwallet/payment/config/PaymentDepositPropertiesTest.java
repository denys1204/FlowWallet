package com.flowwallet.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing else in this suite starts a Spring context, so a typo in a property key or a prefix would
 * otherwise go unnoticed until someone booted the application by hand. These tests bind the real class
 * against real property values, which is the cheapest way to keep that from happening.
 */
class PaymentDepositPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PaymentDepositProperties.class);

    @Test
    @DisplayName("falls back to the shipped bounds when nothing is configured")
    void defaultsMatchWhatShipped() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PaymentDepositProperties limits = context.getBean(PaymentDepositProperties.class);
            assertThat(limits.getMinAmount()).isEqualByComparingTo("1.00");
            assertThat(limits.getMaxAmount()).isEqualByComparingTo("10000.00");
        });
    }

    @Test
    @DisplayName("binds the documented property keys")
    void bindsConfiguredValues() {
        runner.withPropertyValues(
                "payment.deposit.min-amount=0.50",
                "payment.deposit.max-amount=250000.00"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PaymentDepositProperties limits = context.getBean(PaymentDepositProperties.class);
            assertThat(limits.getMinAmount()).isEqualByComparingTo("0.50");
            assertThat(limits.getMaxAmount()).isEqualByComparingTo("250000.00");
        });
    }

    @Test
    @DisplayName("an inverted range fails startup instead of rejecting every payment in silence")
    void invertedRangeFailsStartup() {
        runner.withPropertyValues(
                "payment.deposit.min-amount=100.00",
                "payment.deposit.max-amount=10.00"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a non-positive bound fails startup")
    void nonPositiveMinimumFailsStartup() {
        runner.withPropertyValues("payment.deposit.min-amount=0").run(
                context -> assertThat(context).hasFailed()
        );
    }
}
