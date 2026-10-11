package com.flowwallet.payment.transaction;

import com.flowwallet.payment.support.Concurrently;
import com.flowwallet.payment.support.DatabaseFaults;
import com.flowwallet.payment.support.PaymentIntegrationTest;
import com.flowwallet.payment.support.StripeFixtures;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * A client's concurrent duplicates of its own deposit against the real unique index and row lock: the reservation, the
 * explaining read after a lost one, and the recording of Stripe's answer.
 * See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
 */
class DepositInitiationRaceIT extends PaymentIntegrationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void eightRequestsWithOneKeyAndTheSameTermsReserveOneRowAndAnswerWithOneIntentOrAskForARetry() throws Exception {
        // Guards ADR 0024: a request that loses the reservation to its own twin must not get a 409, which sends the
        // client to a new key and a second payment, nor a 500, but the same intent or a 503 that says to retry under
        // the same key; and every Stripe call goes out under the reference as its idempotency key, so Stripe makes
        // one intent however many requests reach it. Two triggers make the race certain rather than left to
        // scheduling: the reservation's insert waits half a second, so every request reads an empty table and all but
        // one collide on the unique index, and the recording of Stripe's answer waits half a second, so the losers'
        // explaining read finds the winner's row reserved but not yet initiated.
        String userId = newUserId();
        String reference = newKey();
        String intentId = "pi_" + UUID.randomUUID().toString().replace("-", "");
        PaymentIntent intent = StripeFixtures.paymentIntent(intentId, "requires_payment_method", 1000, "pln");
        doReturn(intent).when(stripeClient).createPaymentIntent(any(), eq(reference));

        List<EntityExchangeResult<String>> answers;
        try (var slowReservation = DatabaseFaults.delayInsert(
                jdbc, "payment_transactions", "transaction_reference", reference, 0.5);
             var slowRecording = DatabaseFaults.delayUpdate(
                     jdbc, "payment_transactions", "transaction_reference", reference, 0.5)) {
            answers = Concurrently.run(8, thread -> initiate(userId, reference));
        }

        assertThat(answers).extracting(answer -> answer.getStatus().value()).containsOnly(200, 503).contains(200, 503);
        List<String> bodies = answers.stream()
                .filter(answer -> answer.getStatus().value() == 200)
                .map(EntityExchangeResult::getResponseBody)
                .toList();
        assertThat(bodies).containsOnly(bodies.getFirst());
        JsonNode body = json.readTree(bodies.getFirst());
        assertThat(body.get("paymentIntentId").asString()).isEqualTo(intentId);
        assertThat(body.get("providerData").get("clientSecret").asString()).isEqualTo(intent.getClientSecret());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE transaction_reference = ?",
                Integer.class,
                reference
        )).isOne();
        assertThat(column("status", reference)).isEqualTo("PENDING");
        assertThat(column("provider_transaction_id", reference)).isEqualTo(intentId);
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(stripeClient, atLeastOnce()).createPaymentIntent(params.capture(), keys.capture());
        assertThat(keys.getAllValues()).containsOnly(reference).hasSizeLessThanOrEqualTo(bodies.size());
        assertThat(params.getAllValues()).allSatisfy(sent -> {
            assertThat(sent.getAmount()).isEqualTo(1000L);
            assertThat(sent.getCurrency()).isEqualTo("pln");
        });

        EntityExchangeResult<String> retry = initiate(userId, reference);

        assertThat(retry.getStatus().value()).isEqualTo(200);
        assertThat(retry.getResponseBody()).isEqualTo(bodies.getFirst());
    }

    private EntityExchangeResult<String> initiate(String userId, String reference) {
        return http.post()
                .uri("/api/payments/intent")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"transactionReference": "%s", "amount": "10.00", "currency": "PLN", "providerName": "STRIPE"}
                        """.formatted(reference))
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private String column(String name, String reference) {
        return jdbc.queryForObject(
                "SELECT " + name + " FROM payment_transactions WHERE transaction_reference = ?",
                String.class,
                reference
        );
    }
}
