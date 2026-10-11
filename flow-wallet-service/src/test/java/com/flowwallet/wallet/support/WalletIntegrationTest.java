package com.flowwallet.wallet.support;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base of every Wallet Service integration test. The containers start once per test JVM and every test class
 * shares one Spring context: all its settings live here, because a second context would add a second listener to
 * the consumer group. Tests isolate themselves with fresh user ids, keys and event ids rather than by cleaning
 * tables, and every test ends with {@link LedgerAudit}.
 * See docs/adr/0039-integration-tests-run-per-service-on-testcontainers.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:integration-test.properties")
@Import(PeerTopics.class)
public abstract class WalletIntegrationTest {
    /**
     * The URL parameter keeps the shipped datasource's {@code logServerErrorDetail=false}, which
     * {@code @ServiceConnection} would otherwise replace with the container's bare URL.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(ContainerImages.POSTGRES)
            .withDatabaseName("wallet_db")
            .withUrlParam("logServerErrorDetail", "false");

    /**
     * Auto-creation is off, so a topic the wallet writes without declaring it fails here as it would on a managed
     * broker.
     */
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(ContainerImages.KAFKA)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");

    protected static final KafkaProbe kafkaProbe;

    static {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new IllegalStateException(
                    "Docker is not reachable: start it (OrbStack locally) or build with -DskipITs");
        }
        Startables.deepStart(POSTGRES, KAFKA).join();
        kafkaProbe = new KafkaProbe(KAFKA.getBootstrapServers());
        Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
        Awaitility.setDefaultPollInterval(Duration.ofMillis(50));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    protected JdbcTemplate jdbc;

    @Value("${spring.kafka.consumer.group-id}")
    protected String consumerGroup;

    protected RestTestClient http;

    @BeforeEach
    void connect() {
        refuseTheDevelopersEnvFile();
        http = RestTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @AfterEach
    void everyLedgerStillAddsUp() {
        assertThat(LedgerAudit.inconsistentWallets(jdbc)).as("wallets whose ledger does not add up").isEmpty();
    }

    protected static String newUserId() {
        return UUID.randomUUID().toString();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected void openWallet(String userId, String currency) {
        http.post()
                .uri("/api/wallets")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"currency\":\"" + currency + "\"}")
                .exchange()
                .expectStatus().isCreated();
    }

    /**
     * Failsafe runs in target/, where neither of the service's optional .env imports finds a file. An IDE that runs
     * a test from the module directory would load the repository's real .env, Stripe keys included.
     */
    private void refuseTheDevelopersEnvFile() {
        List<String> envFiles = environment.getPropertySources().stream()
                .map(PropertySource::getName)
                .filter(name -> name.contains(".env"))
                .toList();
        assertThat(envFiles)
                .as("the integration tests must not read a .env file; run them with the working directory set to "
                        + "the module's target directory")
                .isEmpty();
    }
}
