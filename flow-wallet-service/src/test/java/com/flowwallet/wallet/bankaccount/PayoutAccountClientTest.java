package com.flowwallet.wallet.bankaccount;

import com.flowwallet.wallet.deposit.WalletPaymentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the real client proxy against a local HTTP server. A test that mocks the interface would pass with a
 * client that fails on every real call, as a public interface over package-private records does.
 */
class PayoutAccountClientTest {
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";

    private final AtomicReference<String> request = new AtomicReference<>();
    private HttpServer server;
    private PayoutAccountClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/payments/payout-accounts", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("X-User-Id")
                    + " " + body);
            byte[] answer = "{\"state\": \"READY\", \"last4\": \"2874\", \"currency\": \"PLN\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();

        var properties = new WalletPaymentProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new PayoutAccountClientConfig().payoutAccountClient(RestClient.builder(), properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aBankAccountIsSetWithTheCallersIdAndTheTokenAndItsAnswerIsRead() {
        // Guards a client whose proxy cannot reach its own records, so every live call fails with a 500, and a
        // request that reaches Payment Service without the caller's id.
        PayoutAccountResult result = client.set(USER, new PayoutAccountCommand("btok_1", true));

        assertThat(result).isEqualTo(new PayoutAccountResult("READY", "2874", "PLN"));
        String[] parts = request.get().split(" ", 3);
        assertThat(parts[0]).isEqualTo("PUT");
        assertThat(parts[1]).isEqualTo(USER);
        assertThat(new ObjectMapper().readTree(parts[2]).get("token").stringValue()).isEqualTo("btok_1");
    }

    @Test
    void aBankAccountIsReadWithTheCallersId() {
        // Guards the read sent without the caller's id, which Payment Service refuses with a 401.
        assertThat(client.get(USER).state()).isEqualTo("READY");
        assertThat(request.get()).startsWith("GET " + USER);
    }
}
