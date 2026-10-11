package com.flowwallet.payment.webhook;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.support.Concurrently;
import com.flowwallet.payment.support.DatabaseFaults;
import com.flowwallet.payment.support.PaymentIntegrationTest;
import com.flowwallet.payment.support.StripeFixtures;
import com.flowwallet.payment.support.StripeSignatures;
import com.flowwallet.payment.support.WireSample;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * A deposit from the wallet's request to the record on payment.events: the intent call, the signed webhook, the
 * status change and its outbox row in one transaction, and the record the outbox sends, checked against the golden
 * sample the wallet's tests publish.
 */
class DepositSettlementIT extends PaymentIntegrationTest {
    private static final String WEBHOOK_PATH = "/api/payments/webhooks/stripe";

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aSucceededWebhookSettlesTheDepositAndSendsOneCompletedEvent() throws Exception {
        // Guards the payment half of the deposit end to end: a success that is not written with its outbox row, a
        // record whose shape drifts from what the wallet consumes, or a redelivered webhook that sends a second event.
        String userId = newUserId();
        String reference = newKey();
        String intentId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        startDeposit(userId, reference, intentId);
        verify(stripeClient).createPaymentIntent(any(), eq(reference));
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);
        long created = Instant.now().getEpochSecond();
        String webhook = succeeded("evt_" + UUID.randomUUID(), created, intentId);

        postWebhook(WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, WEBHOOK_SECRET))
                .expectStatus().isOk();

        assertThat(status(reference)).isEqualTo("SUCCESS");
        assertThat(outboxRows(reference)).isOne();
        ConsumerRecord<String, String> record = kafkaProbe.awaitRecord(start, r -> reference.equals(r.key()));
        assertThat(new String(record.headers().lastHeader(KafkaConstants.HEADER_EVENT_TYPE).value(),
                StandardCharsets.UTF_8)).isEqualTo(KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED);
        WireSample.of("payment-completed").assertSameShape(record.value());
        JsonNode event = json.readTree(record.value());
        assertThat(event.get("transactionReference").asString()).isEqualTo(reference);
        assertThat(event.get("providerTransactionId").asString()).isEqualTo(intentId);
        assertThat(event.get("userId").asString()).isEqualTo(userId);
        assertThat(event.get("currency").asString()).isEqualTo("PLN");
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(Instant.parse(event.get("completedAt").asString())).isEqualTo(Instant.ofEpochSecond(created));

        postWebhook(WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, WEBHOOK_SECRET))
                .expectStatus().isOk();

        assertThat(status(reference)).isEqualTo("SUCCESS");
        assertThat(outboxRows(reference)).isOne();
    }

    @Test
    void aWebhookSignedWithAnotherSecretIsRefusedAndChangesNothing() throws Exception {
        // Guards a forged success: without the endpoint's secret a webhook must not settle a payment (ADR 0017).
        String reference = newKey();
        String intentId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        startDeposit(newUserId(), reference, intentId);
        String webhook = succeeded("evt_" + UUID.randomUUID(), Instant.now().getEpochSecond(), intentId);

        postWebhook(WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, "whsec_someone-elses-secret"))
                .expectStatus().isBadRequest();

        assertThat(status(reference)).isEqualTo("PENDING");
        assertThat(outboxRows(reference)).isZero();
    }

    @Test
    void aSuccessWhoseOutboxRowCannotBeWrittenIsNotRecordedAndStripesRetrySettlesItOnce() throws Exception {
        // Guards a payment marked SUCCESS with no event behind it, which no one would ever credit: the status change
        // and the outbox row commit together or not at all, and Stripe's redelivery then settles it once (ADR 0008).
        String reference = newKey();
        String intentId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        startDeposit(newUserId(), reference, intentId);
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);
        String webhook = succeeded("evt_" + UUID.randomUUID(), Instant.now().getEpochSecond(), intentId);

        try (var fault = DatabaseFaults.failInsert(jdbc, "outbox_events", "aggregate_id", reference)) {
            postWebhook(WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, WEBHOOK_SECRET))
                    .expectStatus().is5xxServerError();

            assertThat(status(reference)).isEqualTo("PENDING");
            assertThat(outboxRows(reference)).isZero();
        }

        postWebhook(WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, WEBHOOK_SECRET))
                .expectStatus().isOk();

        assertThat(status(reference)).isEqualTo("SUCCESS");
        assertThat(outboxRows(reference)).isOne();
        kafkaProbe.awaitRecord(start, r -> reference.equals(r.key()));
    }

    @Test
    void eightDeliveriesOfOneWebhookAtOnceSettleTheDepositOnceAndAreAllAcknowledged() throws Exception {
        // Guards a second PaymentCompletedEvent, or a 5xx that has Stripe deliver a settled payment again, when Stripe
        // delivers one event several times at once: each delivery passes the event id check before any commits, so
        // only the row's version lets one through, and the optimistic-lock retry must answer the rest from the
        // settled row. The outbox insert under this reference waits half a second inside each transaction, so all
        // eight read the PENDING row before the first one commits and the race is certain, not left to scheduling.
        String reference = newKey();
        String intentId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        startDeposit(newUserId(), reference, intentId);
        String webhook = succeeded("evt_" + UUID.randomUUID(), Instant.now().getEpochSecond(), intentId);

        List<Integer> statuses;
        try (var delay = DatabaseFaults.delayInsert(jdbc, "outbox_events", "aggregate_id", reference, 0.5)) {
            statuses = Concurrently.run(8, thread -> postWebhook(
                    WEBHOOK_PATH,
                    webhook,
                    StripeSignatures.header(webhook, WEBHOOK_SECRET)
            ).expectBody(String.class).returnResult().getStatus().value());
        }

        assertThat(statuses).hasSize(8).allSatisfy(status -> assertThat(status).isBetween(200, 299));
        assertThat(status(reference)).isEqualTo("SUCCESS");
        assertThat(outboxRows(reference)).isOne();
    }

    private void startDeposit(String userId, String reference, String intentId) throws Exception {
        doReturn(StripeFixtures.paymentIntent(intentId, "requires_payment_method", 1000, "pln"))
                .when(stripeClient).createPaymentIntent(any(), eq(reference));
        http.post()
                .uri("/api/payments/intent")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"transactionReference": "%s", "amount": "10.00", "currency": "PLN", "providerName": "STRIPE"}
                        """.formatted(reference))
                .exchange()
                .expectStatus().isOk();
        assertThat(status(reference)).isEqualTo("PENDING");
    }

    private static String succeeded(String eventId, long created, String intentId) {
        return StripeFixtures.paymentIntentEvent(
                eventId,
                "payment_intent.succeeded",
                created,
                intentId,
                "succeeded",
                1000,
                "pln"
        );
    }

    private String status(String reference) {
        return jdbc.queryForObject(
                "SELECT status FROM payment_transactions WHERE transaction_reference = ?",
                String.class,
                reference
        );
    }

    private int outboxRows(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                reference
        );
    }
}
