package com.flowwallet.payment.contract;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the shape of what goes onto {@code payment.events}.
 * <p>
 * Producer and consumer are deployed separately and a topic keeps messages written by more than one
 * version of the code, so renaming or dropping a field breaks readers that this repository compiles
 * perfectly well against. The compiler cannot see that; these tests can.
 * <p>
 * What they guard is the set of field names and a value round-trip. What they deliberately do not pin
 * is the encoding of timestamps and decimals, which comes from a serializer configuration both sides
 * share — an end-to-end test through a real broker is the honest place to check that.
 * <p>
 * A failure here is not a bug in the test. It means the contract changed, and the question to answer is
 * whether every consumer can still read what is already on the topic.
 */
class PaymentEventWireFormatTest {
    private static final Set<String> COMPLETED_FIELDS = Set.of(
            "eventId", "schemaVersion", "transactionReference", "providerTransactionId",
            "amount", "currency", "userId", "completedAt"
    );

    private static final Set<String> FAILED_FIELDS = Set.of(
            "eventId", "schemaVersion", "transactionReference", "providerTransactionId",
            "amount", "currency", "userId", "reason", "failedAt"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    private PaymentCompletedEvent completed() {
        return new PaymentCompletedEvent(
                "2f8a1c34-0000-4000-8000-000000000001", 1,
                "ref-1", "pi_test_1",
                new BigDecimal("50.00"), "USD",
                "user-1",
                Instant.parse("2026-01-15T10:30:00Z")
        );
    }

    private PaymentFailedEvent failed() {
        return new PaymentFailedEvent(
                "2f8a1c34-0000-4000-8000-000000000002", 1,
                "ref-2", "pi_test_2",
                new BigDecimal("50.00"), "USD",
                "user-1",
                "card_declined",
                Instant.parse("2026-01-15T10:31:00Z")
        );
    }

    @Test
    @DisplayName("PaymentCompletedEvent carries exactly the agreed field names")
    void completedEventFieldNamesAreStable() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(completed()));

        assertThat(json.propertyNames()).containsExactlyInAnyOrderElementsOf(COMPLETED_FIELDS);
    }

    @Test
    @DisplayName("PaymentFailedEvent carries exactly the agreed field names")
    void failedEventFieldNamesAreStable() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(failed()));

        assertThat(json.propertyNames()).containsExactlyInAnyOrderElementsOf(FAILED_FIELDS);
    }

    @Test
    @DisplayName("a completed event survives a round trip unchanged")
    void completedEventRoundTrips() {
        PaymentCompletedEvent original = completed();

        PaymentCompletedEvent back = mapper.readValue(
                mapper.writeValueAsString(original),
                PaymentCompletedEvent.class
        );

        assertThat(back).isEqualTo(original);
    }

    @Test
    @DisplayName("a failed event survives a round trip unchanged")
    void failedEventRoundTrips() {
        PaymentFailedEvent original = failed();

        PaymentFailedEvent back = mapper.readValue(
                mapper.writeValueAsString(original),
                PaymentFailedEvent.class
        );

        assertThat(back).isEqualTo(original);
    }

    @Test
    @DisplayName("the schema version stamped on events matches the one the contract declares")
    void schemaVersionIsTheDeclaredOne() {
        assertThat(completed().schemaVersion()).isEqualTo(
                KafkaConstants.PAYMENT_EVENT_SCHEMA_VERSION
        );
    }
}
