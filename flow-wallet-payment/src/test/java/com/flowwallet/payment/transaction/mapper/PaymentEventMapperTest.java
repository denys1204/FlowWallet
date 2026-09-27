package com.flowwallet.payment.transaction.mapper;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.PaymentCompletedEvent;
import com.flowwallet.contract.event.PaymentFailedEvent;
import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.transaction.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventMapperTest {
    private static final Instant SETTLED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String RANDOM_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private final PaymentEventMapper mapper = new PaymentEventMapperImpl();

    @Test
    void completedEventCarriesAmountAndCurrency() {
        PaymentCompletedEvent event = mapper.toPaymentCompletedEvent(transaction(), SETTLED_AT);

        assertThat(event.transactionReference()).isEqualTo("ref-1");
        assertThat(event.amount()).isEqualByComparingTo("50.00");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.userId()).isEqualTo("user-1");
    }

    @Test
    void completedEventCarriesTheProvidersSettlementTimeRatherThanTheProcessingTime() {
        // The contract promises when the provider confirmed the payment. The build ignores unmapped targets, so
        // a renamed parameter would silently leave completedAt null; this pins it.
        PaymentCompletedEvent event = mapper.toPaymentCompletedEvent(transaction(), SETTLED_AT);

        assertThat(event.completedAt()).isEqualTo(SETTLED_AT);
    }

    @Test
    void failedEventCarriesAmountCurrencyAndReason() {
        PaymentFailedEvent event = mapper.toPaymentFailedEvent(transaction(), "card declined", SETTLED_AT);

        assertThat(event.transactionReference()).isEqualTo("ref-1");
        assertThat(event.amount()).isEqualByComparingTo("50.00");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.reason()).isEqualTo("card declined");
        assertThat(event.failedAt()).isEqualTo(SETTLED_AT);
    }

    @Test
    void eachCompletedEventGetsItsOwnEventIdAndTheDeclaredSchemaVersion() {
        // Both fields come from MapStruct expressions, and the build ignores unmapped targets: a dropped expression
        // would send a null eventId, which the wallet dead-letters, or a zero schemaVersion.
        PaymentCompletedEvent first = mapper.toPaymentCompletedEvent(transaction(), SETTLED_AT);
        PaymentCompletedEvent second = mapper.toPaymentCompletedEvent(transaction(), SETTLED_AT);

        assertThat(first.eventId()).matches(RANDOM_UUID).isNotEqualTo(second.eventId());
        assertThat(second.eventId()).matches(RANDOM_UUID);
        assertThat(first.schemaVersion()).isEqualTo(KafkaConstants.PAYMENT_EVENT_SCHEMA_VERSION);
    }

    @Test
    void eachFailedEventGetsItsOwnEventIdAndTheDeclaredSchemaVersion() {
        // The same expressions on the failure mapping; a copy-paste slip there would pass the completed-event test.
        PaymentFailedEvent first = mapper.toPaymentFailedEvent(transaction(), "card declined", SETTLED_AT);
        PaymentFailedEvent second = mapper.toPaymentFailedEvent(transaction(), "card declined", SETTLED_AT);

        assertThat(first.eventId()).matches(RANDOM_UUID).isNotEqualTo(second.eventId());
        assertThat(second.eventId()).matches(RANDOM_UUID);
        assertThat(first.schemaVersion()).isEqualTo(KafkaConstants.PAYMENT_EVENT_SCHEMA_VERSION);
    }

    @Test
    void mapsTransactionToPaymentIntentResponse() {
        PaymentTransaction tx = transaction();
        tx.markAsInitiated("pi_9", Map.of("clientSecret", "cs_9"));

        PaymentIntentResponse response = mapper.toResponse(tx);

        assertThat(response.paymentIntentId()).isEqualTo("pi_9");
        assertThat(response.transactionReference()).isEqualTo("ref-1");
        assertThat(response.providerData()).containsEntry("clientSecret", "cs_9");
    }

    private PaymentTransaction transaction() {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                "ref-1",
                new BigDecimal("50.00"),
                "USD",
                "STRIPE"
        );
        return PaymentTransaction.create(request, "user-1", PaymentProvider.STRIPE);
    }
}
