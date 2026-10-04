package com.flowwallet.gateway.config;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * The gateway's own {@code application.yml} as {@code key=value} pairs, for an {@code ApplicationContextRunner}
 * that binds it the way the running application does instead of restating the expected values as literals.
 * <p>
 * Each {@code ${VAR:default}} is resolved against the file alone, so a test sees the shipped default even in a shell
 * that exports the variable, such as a container's {@code GATEWAY_ADDRESS=0.0.0.0}.
 */
final class ApplicationYaml {
    private ApplicationYaml() {
    }

    static String[] properties() throws IOException {
        MapPropertySource yaml = (MapPropertySource) new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml);
        PropertySourcesPropertyResolver fileOnly = new PropertySourcesPropertyResolver(sources);
        return yaml.getSource().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + fileOnly.resolvePlaceholders(String.valueOf(entry.getValue())))
                .toArray(String[]::new);
    }
}
