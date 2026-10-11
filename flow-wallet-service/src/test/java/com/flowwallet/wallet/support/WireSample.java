package com.flowwallet.wallet.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

/**
 * A golden wire sample from flow-wallet-contract's test-jar ({@code wire/*.json}), filled with a test's own ids. The
 * producer's integration test checks the records it sends against the same file, so a consumer test that publishes a
 * sample consumes what the other service really sends. Floats are read as {@code BigDecimal} and trailing zeros kept,
 * so an amount the test leaves alone goes out as the sample writes it.
 */
public final class WireSample {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private final ObjectNode node;

    private WireSample(ObjectNode node) {
        this.node = node;
    }

    /**
     * @param name the sample's file name without {@code .json}, such as {@code payment-completed}
     */
    public static WireSample of(String name) {
        String path = "wire/" + name + ".json";
        try (InputStream in = WireSample.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No wire sample on the classpath at " + path);
            }
            return new WireSample((ObjectNode) MAPPER.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public WireSample with(String field, String value) {
        node.put(field, value);
        return this;
    }

    public WireSample with(String field, BigDecimal value) {
        node.put(field, value);
        return this;
    }

    public String json() {
        return MAPPER.writeValueAsString(node);
    }
}
