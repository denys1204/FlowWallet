package com.flowwallet.wallet.deposit;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.support.PaymentStub;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import com.flowwallet.wallet.support.WireSample;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The wallet half of a deposit end to end: the HTTP call that starts it, the call to Payment Service, and the completed
 * payment that comes back over Kafka, published as the golden sample Payment Service's tests check it sends.
 */
class DepositFlowIT extends WalletIntegrationTest {
    private static final String INTENT_PATH = "/api/payments/intent";

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aDepositStartedThroughTheWalletIsCreditedWhenItsPaymentCompletes() {
        // Guards the deposit as a client sees it: the caller's key becomes the payment's reference, the caller's id and
        // the amount reach Payment Service as its wire format expects, the provider's data comes back to the client,
        // and the completed payment shows up in the wallet's balance.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String key = newKey();
        paymentStub.answer(INTENT_PATH, 200, """
                {"providerData": {"clientSecret": "pi_flow_secret"}, "paymentIntentId": "pi_flow",
                 "transactionReference": "%s"}
                """.formatted(key));

        String response = http.post()
                .uri("/api/wallets/PLN/deposits")
                .header("X-User-Id", userId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": \"10.00\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        JsonNode deposit = json.readTree(response);
        assertThat(deposit.get("reference").asString()).isEqualTo(key);
        assertThat(deposit.get("providerData").get("clientSecret").asString()).isEqualTo("pi_flow_secret");
        PaymentStub.Request call = paymentStub.requests(INTENT_PATH).getFirst();
        assertThat(call.header("X-User-Id")).isEqualTo(userId);
        JsonNode command = json.readTree(call.body());
        assertThat(command.get("transactionReference").asString()).isEqualTo(key);
        assertThat(command.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(command.get("currency").asString()).isEqualTo("PLN");

        kafkaProbe.publish(
                KafkaConstants.PAYMENT_EVENTS_TOPIC,
                key,
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED,
                WireSample.of("payment-completed")
                        .with("eventId", UUID.randomUUID().toString())
                        .with("transactionReference", key)
                        .with("userId", userId)
                        .json()
        );

        await().untilAsserted(() -> assertThat(walletBalance(userId)).isEqualTo("10.00"));
    }

    @Test
    void aDepositToAWalletTheCallerDoesNotHoldIsRefusedBeforePaymentServiceIsCalled() {
        // Guards a card charged for a wallet that cannot be credited: the wallet is checked first, so nothing reaches
        // Payment Service and no payment exists to refund (ADR 0013).
        String userId = newUserId();

        http.post()
                .uri("/api/wallets/PLN/deposits")
                .header("X-User-Id", userId)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": \"10.00\"}")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(paymentStub.requests(INTENT_PATH)).isEmpty();
    }

    private String walletBalance(String userId) {
        String wallet = http.get()
                .uri("/api/wallets/PLN")
                .header("X-User-Id", userId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return json.readTree(wallet).get("balance").asString();
    }
}
