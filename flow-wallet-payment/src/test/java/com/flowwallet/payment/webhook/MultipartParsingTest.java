package com.flowwallet.payment.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.multipart.MultipartResolver;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With a multipart resolver, {@code DispatcherServlet} reads and spools a multipart body before any controller runs,
 * so an unauthenticated request would get past the webhook size cap. See
 * docs/adr/0017-webhooks-verified-before-they-are-read.md.
 */
class MultipartParsingTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class));

    @Test
    void theShippedConfigurationRegistersNoMultipartResolver() throws IOException {
        // Guards against the setting being dropped from application.yml, which would reopen that path.
        // Only the YAML document is added, not its spring.config.import, so no local .env is read.
        List<PropertySource<?>> shipped = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        runner.withInitializer(context -> shipped.forEach(context.getEnvironment().getPropertySources()::addLast))
                .run(context -> assertThat(context).doesNotHaveBean(MultipartResolver.class));
    }

    @Test
    void withoutThatSettingBootRegistersAMultipartResolver() {
        // Keeps the test above honest: if Boot stopped registering a resolver by default, it would pass for the
        // wrong reason.
        runner.run(context -> assertThat(context).hasSingleBean(MultipartResolver.class));
    }
}
