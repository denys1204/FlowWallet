package com.flowwallet.payment.payout;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PayoutProperties.class);

    @Test
    void theDefaultOffersPlnFromFiveZloty() {
        // Guards a default that offers a currency the PL platform cannot pay out, or a minimum below the 5 PLN Stripe
        // documents, which Stripe would refuse at the payout step after the transfer.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PayoutProperties.PayoutCurrency only = context.getBean(PayoutProperties.class).getCurrencies().getFirst();

            assertThat(context.getBean(PayoutProperties.class).getCurrencies()).hasSize(1);
            assertThat(only.getCode()).isEqualTo("PLN");
            assertThat(only.getMinAmount()).isEqualByComparingTo("5.00");
        });
    }

    @Test
    void theShippedYamlKeepsTheClassDefault() throws Exception {
        // Guards the YAML entry drifting from the class default: the YAML value is what a run without .env gets.
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml);
        PropertySourcesPropertyResolver shipped = new PropertySourcesPropertyResolver(sources);

        assertThat(shipped.getProperty("payment.payout.currencies[0].code")).isEqualTo("PLN");
        assertThat(shipped.getProperty("payment.payout.currencies[0].min-amount")).isEqualTo("5.00");
    }

    @Test
    void theIndexedKeysReachTheirFields() {
        // Guards a key that stops binding, which would leave a currency on its old minimum without a word.
        runner.withPropertyValues(
                "payment.payout.currencies[0].code=PLN",
                "payment.payout.currencies[0].min-amount=10.00",
                "payment.payout.currencies[1].code=EUR",
                "payment.payout.currencies[1].min-amount=1.00"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PayoutProperties.class).getCurrencies())
                    .extracting(PayoutProperties.PayoutCurrency::getCode)
                    .containsExactly("PLN", "EUR");
        });
    }

    @Test
    void anUnknownCurrencyOrALowerCaseCodeFailsStartup() {
        // Guards a typo that would quietly return every withdrawal in the currency with TERMS_REFUSED.
        runner.withPropertyValues("payment.payout.currencies[0].code=XXZ", "payment.payout.currencies[0].min-amount=5")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.payout.currencies[0].code=pln", "payment.payout.currencies[0].min-amount=5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMinimumThatIsNotPositiveOrOffThePayoutGridFailsStartup() {
        // Guards a minimum no payout can meet exactly, such as 0.50 HUF, and one that lets a zero payout through.
        runner.withPropertyValues(
                "payment.payout.currencies[0].code=HUF",
                "payment.payout.currencies[0].min-amount=0.50"
        ).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.payout.currencies[0].code=PLN", "payment.payout.currencies[0].min-amount=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
