package com.flowwallet.payment.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A golden wire sample from flow-wallet-contract's test-jar ({@code wire/*.json}). A producer's test checks the record
 * it sends against it with {@link #assertSameShape}, and a consumer's test publishes it filled with its own ids, so
 * the two services meet on one file. Floats are read as {@code BigDecimal} and trailing zeros kept, so an amount's
 * scale survives the round trip.
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

    /**
     * Fails unless the JSON has the sample's fields and no others, each of the same JSON type, every decimal at the
     * sample's scale and every timestamp an ISO-8601 instant where the sample has one. Values are the test's to check.
     */
    public void assertSameShape(String actualJson) {
        JsonNode actual = MAPPER.readTree(actualJson);
        assertThat(fieldNames(actual)).as("fields").isEqualTo(fieldNames(node));
        for (String field : fieldNames(node)) {
            JsonNode expected = node.get(field);
            JsonNode value = actual.get(field);
            assertThat(value.getNodeType()).as("JSON type of %s", field).isEqualTo(expected.getNodeType());
            if (expected.isBigDecimal()) {
                assertThat(value.decimalValue().scale()).as("scale of %s", field)
                        .isEqualTo(expected.decimalValue().scale());
            }
            if (expected.isString() && isInstant(expected.asString())) {
                assertThat(isInstant(value.asString())).as("%s as an ISO-8601 instant: %s", field, value).isTrue();
            }
        }
    }

    private static Set<String> fieldNames(JsonNode json) {
        Set<String> names = new TreeSet<>();
        json.propertyNames().forEach(names::add);
        return names;
    }

    private static boolean isInstant(String text) {
        try {
            Instant.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
