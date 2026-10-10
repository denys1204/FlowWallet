package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.deposit.WalletPaymentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Calls the real client proxy against a local HTTP server. A test that mocks the interface would pass with a
 * client that fails on every real call, as a public interface over package-private records does.
 */
class PayoutClientTest {
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";

    private final AtomicReference<Received> request = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> answer = new AtomicReference<>("{\"bankAccount\": \"READY\", "
            + "\"reserveSufficient\": true}");
    private HttpServer server;
    private PayoutClient client;

    private record Received(String method, String path, String user, String contentType, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.set(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-User-Id"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body
            ));
            byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        client = clientFor("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static PayoutClient clientFor(String baseUrl) {
        var properties = new WalletPaymentProperties();
        properties.setBaseUrl(baseUrl);
        return new PayoutClientConfig().payoutClient(RestClient.builder(), properties);
    }

    private static PayoutPreflightCommand command(String amount) {
        return new PayoutPreflightCommand("PLN", new BigDecimal(amount));
    }

    @Test
    void thePreflightSendsTheCallersIdTheCurrencyAndTheAmountAndReadsItsAnswer() {
        // Guards a client whose proxy cannot reach its own records, so every live preflight fails and no withdrawal
        // can be made; a request that reaches Payment Service without the caller's id, which it refuses with a 401;
        // a wrong route; and an amount sent as a string, which Payment Service reads as another type.
        PayoutPreflightResult result = client.preflight(USER, command("75.0000"));

        assertThat(result).isEqualTo(new PayoutPreflightResult("READY", true));
        Received received = request.get();
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.path()).isEqualTo("/api/payments/payouts/preflight");
        assertThat(received.user()).isEqualTo(USER);
        assertThat(received.contentType()).startsWith("application/json");
        JsonNode sent = new ObjectMapper().readTree(received.body());
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("currency", "amount");
        assertThat(sent.get("currency").stringValue()).isEqualTo("PLN");
        assertThat(sent.get("amount").isNumber()).isTrue();
    }

    @ParameterizedTest(name = "an amount of {0}")
    @CsvSource({"75.0000", "100.0000", "0.0100", "123456789012345.0000"})
    void theAmountGoesOutAsAJsonNumberAtScaleFour(String amount) {
        // Guards the amount printed in scientific notation or stripped of its zeros: Payment Service reads a number,
        // and 100 written as 1E+2 would be a different token than the amount that is debited.
        client.preflight(USER, command(amount));

        assertThat(request.get().body()).isEqualTo("{\"currency\":\"PLN\",\"amount\":" + amount + "}");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "an explicit null|{\"bankAccount\": \"NOT_SET\", \"reserveSufficient\": null}",
            "an absent field|{\"bankAccount\": \"NOT_SET\"}"
    })
    void anAnswerWithoutAReserveIsReadAsNull(String description, String body) {
        // Guards a missing reserve failing to deserialise, or reading as false: Payment Service sends none while no
        // bank account is ready, and false would hold the withdrawal as a short reserve instead of refusing it.
        answer.set(body);

        assertThat(client.preflight(USER, command("75.0000"))).isEqualTo(new PayoutPreflightResult("NOT_SET", null));
    }

    @ParameterizedTest(name = "a {0} is a {1}")
    @MethodSource("statusFailures")
    void aRefusalOrAFailureIsRaisedAsTheStatusExceptionThePreflightTranslates(
            int code,
            Class<? extends RuntimeException> type
    ) {
        // Guards a proxy without Spring's status handling, which would hand the preflight an unreadable result for
        // a 400 that carries Payment Service's reason, so it could neither relay the reason nor tell a refusal of
        // the terms from an outage.
        status.set(code);
        answer.set("{\"detail\": \"The amount is below the minimum for PLN.\"}");

        assertThatThrownBy(() -> client.preflight(USER, command("75.0000")))
                .isInstanceOfSatisfying(type, e -> assertThat(e.getMessage()).contains("minimum"));
    }

    static Stream<Arguments> statusFailures() {
        return Stream.of(
                arguments(400, HttpClientErrorException.class),
                arguments(401, HttpClientErrorException.class),
                arguments(502, HttpServerErrorException.class),
                arguments(503, HttpServerErrorException.class)
        );
    }

    @Test
    void aPaymentServiceThatCannotBeReachedIsRaisedAsAResourceAccessException() throws IOException {
        // Guards an unreachable Payment Service failing as another type than the one the preflight turns into a 502
        // that says nothing was debited.
        int closedPort;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        PayoutClient unreachable = clientFor("http://127.0.0.1:" + closedPort);

        assertThatThrownBy(() -> unreachable.preflight(USER, command("75.0000")))
                .isInstanceOf(ResourceAccessException.class);
    }
}
