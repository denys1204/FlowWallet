package com.flowwallet.payment.provider.stripe.config;

import org.junit.jupiter.api.DisplayName;
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
                "stripe.webhook.secret=whsec_unit-test-signing-secret",
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

    @Test
    void theStripeCallDefaultsFitInsideTheWalletsWait() {
        // Guards stripe-java's own defaults (30 s to connect, 80 s to read, two retries) coming back. With them a
        // Payment Service thread stays busy for minutes after the wallet answered its caller at 10 s.
        runner.run(context -> {
            StripeProperties.Api api = context.getBean(StripeProperties.class).getApi();
            assertThat(api.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(api.getReadTimeout()).isEqualTo(Duration.ofSeconds(6));
            assertThat(api.getMaxNetworkRetries()).isZero();
            long worstCase = (1L + api.getMaxNetworkRetries())
                    * (api.getConnectTimeout().toMillis() + api.getReadTimeout().toMillis());
            assertThat(worstCase).isLessThan(Duration.ofSeconds(10).toMillis());
        });
    }

    @ParameterizedTest(name = "{0} fails startup")
    @ValueSource(strings = {
            "stripe.api.connect-timeout=0s",
            "stripe.api.read-timeout=0ms",
            "stripe.api.read-timeout=-1s",
            "stripe.api.read-timeout=30d",
            "stripe.api.max-network-retries=-1"
    })
    void aStripeCallSettingThatCannotWorkFailsStartup(String setting) {
        // Guards a zero timeout, which fails every Stripe call at once while health stays green, a negative retry
        // count, and a timeout past the int of milliseconds stripe-java takes, which would fail on the first call.
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void depositsOfferCardsUnlessConfiguredOtherwise() {
        // Guards the default drifting to an empty or wider set, which would offer methods nobody reviewed.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StripeProperties.class).getPaymentMethodTypes())
                    .containsExactly(StripePaymentMethodType.CARD);
        });
    }

    @ParameterizedTest(name = "\"{0}\" binds to cards")
    @ValueSource(strings = {"card", "CARD", "Card"})
    void theCardMethodBindsInAnyCase(String value) {
        // Guards a case-sensitive lookup refusing CARD from an environment variable while the YAML default card works.
        runner.withPropertyValues("stripe.payment-method-types=" + value).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StripeProperties.class).getPaymentMethodTypes())
                    .containsExactly(StripePaymentMethodType.CARD);
        });
    }

    @ParameterizedTest(name = "\"{0}\" fails startup")
    @ValueSource(strings = {"sepa_debit", "automatic", "card,sepa_debit", "card,", ""})
    void anUnknownOrEmptyPaymentMethodFailsStartup(String value) {
        // Guards a typo or a method nobody reviewed being offered to depositors: delayed and redirect methods
        // were left out on purpose (docs/adr/0028-deposits-accept-cards-only.md).
        runner.withPropertyValues("stripe.payment-method-types=" + value)
                .run(context -> assertThat(context).hasFailed());
    }
}
