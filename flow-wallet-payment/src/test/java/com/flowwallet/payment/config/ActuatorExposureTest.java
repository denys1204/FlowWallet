package com.flowwallet.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the shipped {@code application.yml} with only its own defaults, so neither a local {@code .env} nor an
 * exported variable changes what is checked.
 */
class ActuatorExposureTest {
    private static List<String> exposedByDefault() throws IOException {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml);
        String exposed = new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(
                String.valueOf(yaml.getProperty("management.endpoints.web.exposure.include"))
        );
        return Arrays.asList(exposed.split(","));
    }

    /**
     * Whether Boot would expose the endpoint: each token is trimmed, and every token but the wildcard is read as an
     * endpoint id, so " payouts" and "pay-outs" name the endpoint as well. The wildcard exposes every endpoint.
     */
    private static boolean exposes(List<String> tokens, EndpointId endpoint) {
        List<String> trimmed = tokens.stream().map(String::trim).toList();
        return trimmed.contains("*") || trimmed.stream()
                .map(EndpointId::fromPropertyValue)
                .anyMatch(endpoint::equals);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "health,payouts",
            "health, payouts",
            "health,payouts ",
            "health,pay-outs",
            "payouts,health",
            "*",
            "health, *",
            "health,* "
    })
    void aListBootReadsAsExposingTheEndpointIsRecognised(String list) {
        // Guards the check of the default being blind to a spelling Boot accepts: with a comparison of raw text, a
        // padded, hyphenated or wildcard entry would slip past it and the test of the default would pass for the
        // list that exposes the endpoint.
        assertThat(exposes(List.of(list.split(",")), EndpointId.of("payouts"))).isTrue();
    }

    @Test
    void aListWithoutTheEndpointOrAWildcardDoesNotExposeIt() {
        // Guards the check of the default failing every list, which would make the test of the default a test of the
        // check alone.
        assertThat(exposes(List.of("health", " info", "metrics ", "outbox"), EndpointId.of("payouts"))).isFalse();
    }

    @Test
    void theOutboxEndpointIsExposedByDefault() throws Exception {
        // Guards the only way out short of SQL for a FAILED outbox row, which here is a payment or payout event the
        // wallet never received. See docs/adr/0008-transactional-outbox.md.
        assertThat(exposes(exposedByDefault(), EndpointId.of("outbox"))).isTrue();
    }

    @Test
    void thePayoutsEndpointIsNotExposedByDefault() throws Exception {
        // Guards an unauthenticated write that credits a wallet being opened by a default: the endpoint's actions
        // return a payout's money to the user, and nothing on Payment Service's port asks who is calling. A wildcard
        // exposes it too, and so does a spelling Boot reads as the same id, such as a padded or hyphenated one.
        // See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
        assertThat(exposes(exposedByDefault(), EndpointId.of("payouts"))).isFalse();
    }
}
