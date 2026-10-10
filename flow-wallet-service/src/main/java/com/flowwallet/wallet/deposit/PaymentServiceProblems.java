package com.flowwallet.wallet.deposit;

import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

import java.util.StringJoiner;

/**
 * Reads the reason out of a problem+json refusal from Payment Service, for the wallet to pass on as its own 400.
 */
public final class PaymentServiceProblems {
    private PaymentServiceProblems() {
    }

    /**
     * Prefers the field-level {@code errors} over {@code detail}: for a bean-validation failure, {@code detail} is
     * Spring's generic "Invalid request content." and the broken rule is only in {@code errors}. Anything unexpected
     * falls back to the given wording rather than throwing. See docs/adr/0013-deposit-initiation.md.
     */
    public static String reasonOf(HttpClientErrorException e, ObjectMapper objectMapper, String fallback) {
        try {
            var body = objectMapper.readTree(e.getResponseBodyAsString());

            var errors = body.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                var joined = new StringJoiner("; ");
                errors.forEach(error -> joined.add(error.stringValue()));
                return joined.toString();
            }

            var detail = body.get("detail");
            if (detail != null && !detail.stringValue().isBlank()) {
                return detail.stringValue();
            }
        } catch (RuntimeException ignored) {
            // Falls through to the fallback below.
        }
        return fallback;
    }
}
