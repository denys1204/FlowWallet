package com.flowwallet.payment.support;

import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.stripe.Stripe;
import org.awaitility.Awaitility;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base of every Payment Service integration test. The containers start once per test JVM and every test class
 * shares one Spring context: all its settings live here, because a second context would add a second listener to
 * the consumer group. Tests isolate themselves with fresh keys and ids rather than by cleaning tables.
 * <p>
 * Stripe never answers: {@link StripeClient} is a spy whose network methods each test stubs, while the webhook
 * signature check stays real, and any call left unstubbed goes to a closed local port and fails at once instead of
 * reaching Stripe. See docs/adr/0039-integration-tests-run-per-service-on-testcontainers.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:integration-test.properties")
@Import(PeerTopics.class)
public abstract class PaymentIntegrationTest {
    /**
     * The webhook secret in integration-test.properties: a test signs its webhooks with it.
     */
    protected static final String WEBHOOK_SECRET = "whsec_integration-test-signing-secret";

    /**
     * The URL parameter keeps the shipped datasource's {@code logServerErrorDetail=false}, which
     * {@code @ServiceConnection} would otherwise replace with the container's bare URL.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(ContainerImages.POSTGRES)
            .withDatabaseName("payment_db")
            .withUrlParam("logServerErrorDetail", "false");

    /**
     * Auto-creation is off, so a topic the service writes without declaring it fails here as it would on a managed
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
        // Port 9 on loopback is closed, so a Stripe call no test stubbed fails with ApiConnectionException.
        Stripe.overrideApiBase("http://127.0.0.1:9");
        Stripe.overrideConnectBase("http://127.0.0.1:9");
        Startables.deepStart(POSTGRES, KAFKA).join();
        kafkaProbe = new KafkaProbe(KAFKA.getBootstrapServers());
        Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
        Awaitility.setDefaultPollInterval(Duration.ofMillis(50));
    }

    @MockitoSpyBean
    protected StripeClient stripeClient;

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

    protected static String newUserId() {
        return UUID.randomUUID().toString();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Posts a webhook body as Stripe would: the raw bytes, signed with the endpoint's secret.
     */
    protected RestTestClient.ResponseSpec postWebhook(String path, String payload, Map<String, String> headers) {
        return http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers.forEach(h::add))
                .body(payload)
                .exchange();
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
