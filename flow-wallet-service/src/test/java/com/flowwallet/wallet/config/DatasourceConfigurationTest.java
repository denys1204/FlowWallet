package com.flowwallet.wallet.config;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the shipped {@code application.yml} with only its own defaults, so neither a local {@code .env} nor an
 * exported variable changes what is checked.
 */
class DatasourceConfigurationTest {
    private static final String USER_ID = "67b8b07e-bb16-4c92-9747-41d98f774795";

    private static String shipped(String key) throws Exception {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml);
        return new PropertySourcesPropertyResolver(sources)
                .resolveRequiredPlaceholders(String.valueOf(yaml.getProperty(key)));
    }

    @Test
    void aConstraintViolationMessageNamesTheConstraintButNotTheRefusedValues() throws Exception {
        // Guards the driver setting being dropped from the URL: pgjdbc would put Postgres' DETAIL line, with the
        // user id of a duplicate wallet or the whole failing row, into every log that prints the exception.
        Properties urlProperties = Driver.parseURL(shipped("spring.datasource.url"), new Properties());
        assertThat(urlProperties).isNotNull();
        boolean withDetail = PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(urlProperties);
        assertThat(withDetail).isFalse();

        ServerErrorMessage serverError = new ServerErrorMessage(
                "SERROR\0C23505"
                        + "\0Mduplicate key value violates unique constraint \"wallets_user_id_currency_key\""
                        + "\0DKey (user_id, currency)=(" + USER_ID + ", EUR) already exists.\0"
        );
        PSQLException exception = new PSQLException(serverError, withDetail);

        assertThat(exception.getMessage())
                .contains("wallets_user_id_currency_key")
                .doesNotContain(USER_ID);
        assertThat(exception.getServerErrorMessage().getDetail()).contains(USER_ID);
    }

    @Test
    void aRequestWaitsAtMostFiveSecondsForAPooledConnection() throws Exception {
        // Guards the pool timeout being dropped: HikariCP's own 30000 ms outlasts the gateway's 20 s response
        // timeout, so a dead database reached the client as the gateway's 504 instead of this service's 503.
        assertThat(shipped("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
    }
}
