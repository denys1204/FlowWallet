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

import java.io.IOException;
import java.time.Duration;
import java.util.List;

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

    @Test
    void theShippedYamlCarriesTheClassesTestIdentity() throws Exception {
        // Guards the YAML identity drifting from the class: the values pass Stripe's test-mode checks only together,
        // so one changed on its own side would make every account creation fail.
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        ApplicationContextRunner shipped = runner.withInitializer(context -> context.getEnvironment()
                .getPropertySources()
                .addFirst(yaml));

        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            PayoutProperties.Account bound = context.getBean(PayoutProperties.class).getAccount();
            PayoutProperties.Account defaults = new PayoutProperties.Account();

            assertThat(bound).usingRecursiveComparison().isEqualTo(defaults);
        });
    }

    @Test
    void anIdentityStripeCannotTakeFailsStartup() {
        // Guards an account creation that Stripe would refuse on every first bank account request, found only then.
        runner.withPropertyValues("payment.payout.account.country=pl")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.payout.account.merchant-category-code=57340")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.payout.account.tos-ip=localhost")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.payout.account.first-name= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMinimumBelowStripesMinimumPayoutFailsStartup() {
        // Guards a minimum Stripe refuses at the payout step, after the transfer: the money would come back only by
        // a reversal, days later.
        runner.withPropertyValues(
                "payment.payout.currencies[0].code=PLN",
                "payment.payout.currencies[0].min-amount=4.99"
        ).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                "payment.payout.currencies[0].code=PLN",
                "payment.payout.currencies[0].min-amount=5"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aMinimumIsCheckedOnlyWhereStripeListsOneForTheAccountsCountry() {
        // Guards refusing a configuration Stripe's table says nothing about: a currency it lists no minimum for, or a
        // country whose own currency is another.
        runner.withPropertyValues(
                "payment.payout.currencies[0].code=JPY",
                "payment.payout.currencies[0].min-amount=1"
        ).run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(
                "payment.payout.account.country=CZ",
                "payment.payout.currencies[0].code=PLN",
                "payment.payout.currencies[0].min-amount=1.00"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    private static PropertySource<?> loadYaml() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
    }

    @Test
    void theShippedYamlKeepsTheExecutorDefaults() throws IOException {
        // Guards the YAML and the class disagreeing, so a run without .env would use values nobody documented.
        PropertySource<?> yaml = loadYaml();
        ApplicationContextRunner shipped = runner.withInitializer(context -> context.getEnvironment()
                .getPropertySources()
                .addFirst(yaml));

        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PayoutProperties.class).getExecutor())
                    .usingRecursiveComparison()
                    .isEqualTo(new PayoutProperties.Executor());
        });
    }

    @Test
    void anExecutorSettingThatCannotWorkFailsStartup() {
        // Guards a lease too short to hold a claim, a first retry inside a request Stripe still runs, a backoff whose
        // cap is below its start, and a limit that hands every payout to an operator at once.
        for (String setting : List.of(
                "payment.payout.executor.lease=5s",
                "payment.payout.executor.unknown-backoff=10s",
                "payment.payout.executor.unknown-max-backoff=30s",
                "payment.payout.executor.unknown-attempts=0",
                "payment.payout.executor.payout-retry-attempts=0",
                "payment.payout.executor.reversal-retry-attempts=0",
                "payment.payout.executor.interval-ms=500",
                "payment.payout.executor.batch-size=0",
                "payment.payout.executor.run-budget=500ms",
                "payment.payout.executor.payout-retry-interval=500ms",
                "payment.payout.executor.reversal-retry-interval=500ms"
        )) {
            runner.withPropertyValues(setting).run(context -> assertThat(context).as(setting).hasFailed());
        }
    }

    @Test
    void theUnknownBackoffDoublesUpToItsCap() {
        // Guards a backoff that never grows through a long outage, or one that grows past its cap.
        PayoutProperties.Executor executor = new PayoutProperties.Executor();

        assertThat(executor.unknownBackoffAfter(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(executor.unknownBackoffAfter(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(executor.unknownBackoffAfter(6)).isEqualTo(Duration.ofHours(1));
        assertThat(executor.unknownBackoffAfter(40)).isEqualTo(Duration.ofHours(1));
    }
}
