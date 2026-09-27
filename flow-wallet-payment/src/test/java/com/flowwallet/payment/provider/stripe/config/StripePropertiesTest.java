package com.flowwallet.payment.provider.stripe.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real class against real property values, as {@code PaymentDepositPropertiesTest} does.
 */
class StripePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(StripeProperties.class);

    @Test
    @DisplayName("starts without a signing secret, with webhooks disabled")
    void startsWithoutASigningSecret() {
        // The quickstart starts Payment Service before `stripe listen` has printed a secret, so a missing secret
        // must not fail startup; it must only refuse webhooks.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            StripeProperties properties = context.getBean(StripeProperties.class);
            assertThat(properties.getWebhook().getSecret()).isNull();
            assertThat(properties.getWebhook().hasSigningSecret()).isFalse();
            assertThat(properties.getWebhook().getToleranceSeconds()).isEqualTo(300);
        });
    }

    @Test
    @DisplayName("binds a real signing secret and enables webhooks")
    void bindsARealSigningSecret() {
        runner.withPropertyValues(
                "stripe.webhook.secret=whsec_4eC39HqLyjWDarjtT1zdp7dc",
                "stripe.webhook.tolerance-seconds=120"
        ).run(context -> {
            StripeProperties properties = context.getBean(StripeProperties.class);
            assertThat(properties.getWebhook().hasSigningSecret()).isTrue();
            assertThat(properties.getWebhook().getToleranceSeconds()).isEqualTo(120);
        });
    }

    @ParameterizedTest(name = "\"{0}\" leaves webhooks disabled")
    @ValueSource(strings = {"", "whsec_dummy", "whsec_your_secret_here", "whsec_", "sk_test_123"})
    void aPlaceholderOrForeignValueIsNotASigningSecret(String secret) {
        // stripe-java signs with any string, so each of these would let anyone who knows it sign a webhook.
        runner.withPropertyValues("stripe.webhook.secret=" + secret).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StripeProperties.class).getWebhook().hasSigningSecret()).isFalse();
        });
    }

    @ParameterizedTest(name = "tolerance {0} fails startup")
    @ValueSource(strings = {"0", "-1"})
    void aToleranceThatIsNotPositiveFailsStartup(String tolerance) {
        // stripe-java skips the timestamp check for a tolerance of zero or less, which would accept a captured
        // delivery replayed at any later time.
        runner.withPropertyValues("stripe.webhook.tolerance-seconds=" + tolerance)
                .run(context -> assertThat(context).hasFailed());
    }
}
