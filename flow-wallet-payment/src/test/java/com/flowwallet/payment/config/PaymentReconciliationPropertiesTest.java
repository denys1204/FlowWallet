package com.flowwallet.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReconciliationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PaymentReconciliationProperties.class);

    @Test
    void theDefaultsAskAboutAPaymentAfterFifteenMinutesForOneDay() {
        // Guards a default that asks Stripe while the webhook is still expected, or that gives up on a payment
        // before a delayed webhook would plausibly have arrived.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PaymentReconciliationProperties reconciliation = context.getBean(PaymentReconciliationProperties.class);
            assertThat(reconciliation.isEnabled()).isTrue();
            assertThat(reconciliation.getIntervalMs()).isEqualTo(300000);
            assertThat(reconciliation.getMinAge()).isEqualTo(Duration.ofMinutes(15));
            assertThat(reconciliation.getMaxAge()).isEqualTo(Duration.ofHours(24));
            assertThat(reconciliation.getBatchSize()).isEqualTo(50);
        });
    }

    @Test
    void everyDocumentedKeyReachesItsField() {
        // Guards a key whose name stops matching its field: Spring then ignores it without a word.
        runner.withPropertyValues(
                "payment.reconciliation.enabled=false",
                "payment.reconciliation.interval-ms=60000",
                "payment.reconciliation.min-age=2m",
                "payment.reconciliation.max-age=3h",
                "payment.reconciliation.batch-size=7"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PaymentReconciliationProperties reconciliation = context.getBean(PaymentReconciliationProperties.class);
            assertThat(reconciliation.isEnabled()).isFalse();
            assertThat(reconciliation.getIntervalMs()).isEqualTo(60000);
            assertThat(reconciliation.getMinAge()).isEqualTo(Duration.ofMinutes(2));
            assertThat(reconciliation.getMaxAge()).isEqualTo(Duration.ofHours(3));
            assertThat(reconciliation.getBatchSize()).isEqualTo(7);
        });
    }

    @ParameterizedTest(name = "{0} fails startup")
    @ValueSource(strings = {
            "payment.reconciliation.interval-ms=999",
            "payment.reconciliation.batch-size=0",
            "payment.reconciliation.min-age=0s",
            "payment.reconciliation.min-age=24h",
            "payment.reconciliation.max-age=1m"
    })
    void aSettingThatCannotWorkFailsStartup(String setting) {
        // Guards a reconciler that calls Stripe in a tight loop, reads no rows, or has an empty window: min-age at
        // or above max-age matches no payment, so lost webhooks would silently never be recovered.
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }
}
