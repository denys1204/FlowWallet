package com.flowwallet.wallet.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Payment Service as the wallet sees it over HTTP: a real server on a loopback port, so the wallet's own clients and
 * their JSON run unchanged. A test sets the answer for a path and reads back what the wallet sent; a
 * path with no answer gets a 404, so an unexpected call fails the test that made it.
 */
public final class PaymentStub {
    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private PaymentStub(HttpServer server) {
        this.server = server;
    }

    public static PaymentStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            PaymentStub stub = new PaymentStub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the Payment Service stub", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Answers every request to the path with the status and JSON body until {@link #reset}.
     */
    public void answer(String path, int status, String json) {
        answers.put(path, new Answer(status, json));
    }

    public List<Request> requests(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    public void reset() {
        answers.clear();
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            requests.add(new Request(exchange.getRequestMethod(), path, headers, body));

            Answer answer = answers.getOrDefault(
                    path,
                    new Answer(404, "{\"detail\":\"No stubbed answer for " + path + "\"}")
            );
            byte[] bytes = answer.json().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    answer.status() >= 400 ? "application/problem+json" : "application/json"
            );
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * @param headers header names in lower case
     */
    public record Request(String method, String path, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private record Answer(int status, String json) {
    }
}
