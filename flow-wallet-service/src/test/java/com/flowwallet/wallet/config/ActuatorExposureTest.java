package com.flowwallet.wallet.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the shipped {@code application.yml} with only its own defaults, so neither a local {@code .env} nor an
 * exported variable changes what is checked.
 */
class ActuatorExposureTest {
    @Test
    void theOutboxEndpointIsExposedByDefault() throws Exception {
        // Guards the only way out short of SQL for a FAILED outbox row, which is a debited withdrawal Payment Service
        // never received. See docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml);
        String exposed = new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(
                String.valueOf(yaml.getProperty("management.endpoints.web.exposure.include"))
        );

        assertThat(Arrays.asList(exposed.split(","))).contains("outbox");
    }
}
