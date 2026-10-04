package com.flowwallet.gateway.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BindAddressCheckTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(BindAddressCheck.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logs);
    }

    @Test
    void applicationYamlBindsTheGatewayToLoopback() throws Exception {
        // Guards the default drifting back to every interface, which would put an endpoint that trusts any
        // X-User-Id on the local network (docs/adr/0031-callers-are-authenticated-in-front-of-the-gateway.md).
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ServerPropertiesConfig.class)
                .withPropertyValues(ApplicationYaml.properties());

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ServerProperties.class).getAddress())
                    .isNotNull()
                    .extracting(InetAddress::getHostAddress)
                    .isEqualTo("127.0.0.1");
        });
    }

    @Test
    void theCheckRunsWhenTheApplicationIsReady() {
        // Guards the listener being dropped or bound to another event, which the tests that call the method directly
        // cannot see: the gateway would then start on a wide address in silence. Registering the class by name leaves
        // its @Component unchecked; the live start of the gateway covers the component scan.
        new ApplicationContextRunner()
                .withUserConfiguration(ServerPropertiesConfig.class, BindAddressCheck.class)
                .withPropertyValues("server.address=0.0.0.0")
                .run(context -> {
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(),
                            new String[0],
                            context.getSourceApplicationContext(),
                            Duration.ZERO
                    ));

                    assertThat(logs.list).singleElement().satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("0.0.0.0");
                    });
                });
    }

    @ParameterizedTest(name = "{0} logs nothing")
    @ValueSource(strings = {"127.0.0.1", "::1", "localhost"})
    void aLoopbackAddressLogsNothing(String host) throws Exception {
        // Guards a warning on every ordinary local start, which would teach people to ignore it.
        checkFor(InetAddress.getByName(host)).warnWhenReachableBeyondLoopback();

        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest(name = "{0} warns")
    @ValueSource(strings = {"0.0.0.0", "192.168.1.10"})
    void anAddressBeyondLoopbackWarnsAndNamesTheSetting(String host) throws Exception {
        // Guards an address that a container or a LAN can reach being judged safe. 0.0.0.0 is every interface,
        // which is not a loopback address.
        checkFor(InetAddress.getByName(host)).warnWhenReachableBeyondLoopback();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(host, "GATEWAY_ADDRESS");
        });
    }

    @Test
    void noAddressWarnsThatTheGatewayListensOnEveryInterface() {
        // Guards an unset server.address, which Netty takes as every interface, being treated as safe.
        checkFor(null).warnWhenReachableBeyondLoopback();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("every interface");
        });
    }

    private static BindAddressCheck checkFor(InetAddress address) {
        ServerProperties server = new ServerProperties();
        server.setAddress(address);
        return new BindAddressCheck(server);
    }

    @Configuration
    @EnableConfigurationProperties(ServerProperties.class)
    static class ServerPropertiesConfig {
    }
}
