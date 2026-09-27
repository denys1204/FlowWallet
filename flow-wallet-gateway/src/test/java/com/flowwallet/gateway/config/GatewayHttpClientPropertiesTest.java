package com.flowwallet.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the gateway's own {@code application.yml} into Spring Cloud Gateway's {@link HttpClientProperties},
 * the same way the running application does, rather than restating the expected values as literals.
 */
class GatewayHttpClientPropertiesTest {
    @Test
    void applicationYamlBindsAResponseTimeoutAboveTheWalletsOwnDepositTimeoutAndAConnectTimeout() throws Exception {
        // NettyRoutingFilter (Spring Cloud Gateway) applies no response timeout at all when this binds to null,
        // so a route to an upstream that accepts the connection and never answers hangs indefinitely. A YAML
        // typo or reindent that drops these keys out of the httpclient block must fail here, not in a live
        // request left waiting.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(HttpClientPropertiesConfig.class)
                .withPropertyValues(applicationYamlProperties());

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            HttpClientProperties httpClient = context.getBean(HttpClientProperties.class);
            // The wallet's own deposit call to Payment Service is connect 2s + read 10s (WALLET_PAYMENT_*), so
            // this must sit above that: the deposit's own timeout should fire first, and the gateway's is a
            // backstop rather than the thing a caller usually hits.
            assertThat(httpClient.getResponseTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(httpClient.getConnectTimeout()).isEqualTo(2000);
        });
    }

    private static String[] applicationYamlProperties() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        Map<String, Object> properties = ((MapPropertySource) sources.get(0)).getSource();
        return properties.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    @Configuration
    @EnableConfigurationProperties(HttpClientProperties.class)
    static class HttpClientPropertiesConfig {
    }
}
