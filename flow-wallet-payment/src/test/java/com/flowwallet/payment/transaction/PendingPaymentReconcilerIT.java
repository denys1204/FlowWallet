package com.flowwallet.payment.transaction;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

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
 * The deposit reconciler against the real claim query and the real outbox: a paid deposit whose webhook never came
 * is settled from Stripe's answer, and the webhook that arrives late or at the same moment changes nothing.
 */
class PendingPaymentReconcilerIT extends PaymentIntegrationTest {
    private static final String WEBHOOK_PATH = "/api/payments/webhooks/stripe";

    @Autowired
    private PendingPaymentReconciler reconciler;

    @Test
    void aPaidDepositWithoutItsWebhookIsSettledByTheReconcilerAndTheLateWebhookAddsNothing() throws Exception {
        // Guards the one path where a card is charged and the wallet never credited (ADR 0032): the reconciler must
        // select the old PENDING row, settle it through the webhook's own handler with one outbox row, and a webhook
        // arriving afterwards must not send a second event.
        String reference = newKey();
        String intentId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        startBackdatedDeposit(reference, intentId);
        doReturn(StripeFixtures.paymentIntent(intentId, "succeeded", 1000, "pln"))
                .when(stripeClient).retrievePaymentIntent(intentId);
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.PAYMENT_EVENTS_TOPIC);

        reconciler.reconcile();

        assertThat(column("status", reference)).isEqualTo("SUCCESS");
        assertThat(column("provider_event_id", reference)).isEqualTo("reconcile:" + intentId);
        assertThat(outboxRows(reference)).isOne();
        ConsumerRecord<String, String> record = kafkaProbe.awaitRecord(start, r -> reference.equals(r.key()));
        WireSample.of("payment-completed").assertSameShape(record.value());

        String webhook = succeeded("evt_" + UUID.randomUUID(), intentId);
        postWebhook(WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, WEBHOOK_SECRET))
                .expectStatus().isOk();

        assertThat(column("status", reference)).isEqualTo("SUCCESS");
        assertThat(column("provider_event_id", reference)).isEqualTo("reconcile:" + intentId);
        assertThat(outboxRows(reference)).isOne();
    }

    @Test
    void aWebhookArrivingWhileTheReconcilerSettlesTheSameDepositMovesItOnce() throws Exception {
        // Guards a second PaymentCompletedEvent when the webhook and the reconciler apply one success at the same
        // moment: they carry different event ids, so only the row's version lets one through, and the optimistic-lock
        // retry must leave the other with nothing to do and the webhook acknowledged (ADR 0032). The outbox insert
        // under this reference waits a second inside each transaction, so both read the PENDING row before either
        // commits and the race is certain, not left to scheduling.
        String reference = newKey();
        String intentId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        startBackdatedDeposit(reference, intentId);
        doReturn(StripeFixtures.paymentIntent(intentId, "succeeded", 1000, "pln"))
                .when(stripeClient).retrievePaymentIntent(intentId);
        String eventId = "evt_" + UUID.randomUUID();
        String webhook = succeeded(eventId, intentId);

        List<Integer> webhookStatus;
        try (var delay = DatabaseFaults.delayInsert(jdbc, "outbox_events", "aggregate_id", reference, 1.0)) {
            webhookStatus = Concurrently.run(2, thread -> {
                if (thread == 0) {
                    reconciler.reconcile();
                    return null;
                }
                return postWebhook(WEBHOOK_PATH, webhook, StripeSignatures.header(webhook, WEBHOOK_SECRET))
                        .expectBody(String.class)
                        .returnResult()
                        .getStatus()
                        .value();
            });
        }

        assertThat(webhookStatus.get(1)).isEqualTo(200);
        verify(stripeClient).retrievePaymentIntent(intentId);
        assertThat(column("status", reference)).isEqualTo("SUCCESS");
        assertThat(column("provider_event_id", reference)).isIn("reconcile:" + intentId, eventId);
        assertThat(outboxRows(reference)).isOne();
    }

    /**
     * Starts a deposit and moves it past the reconciler's minimum age (15 minutes) and just inside its maximum
     * (24 hours), so the row is due and sorts ahead of any PENDING row another test left, which would hit the Stripe
     * tripwire and count as a failure.
     */
    private void startBackdatedDeposit(String reference, String intentId) throws Exception {
        doReturn(StripeFixtures.paymentIntent(intentId, "requires_payment_method", 1000, "pln"))
                .when(stripeClient).createPaymentIntent(any(), eq(reference));
        http.post()
                .uri("/api/payments/intent")
                .header("X-User-Id", newUserId())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"transactionReference": "%s", "amount": "10.00", "currency": "PLN", "providerName": "STRIPE"}
                        """.formatted(reference))
                .exchange()
                .expectStatus().isOk();
        jdbc.update(
                "UPDATE payment_transactions SET created_at = created_at - interval '23 hours' "
                        + "WHERE transaction_reference = ?",
                reference
        );
    }

    private static String succeeded(String eventId, String intentId) {
        return StripeFixtures.paymentIntentEvent(
                eventId,
                "payment_intent.succeeded",
                Instant.now().getEpochSecond(),
                intentId,
                "succeeded",
                1000,
                "pln"
        );
    }

    private String column(String name, String reference) {
        return jdbc.queryForObject(
                "SELECT " + name + " FROM payment_transactions WHERE transaction_reference = ?",
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
