package com.flowwallet.wallet.withdrawal;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.balance.PaymentEventListener;
import com.flowwallet.wallet.support.Concurrently;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import com.flowwallet.wallet.support.WireSample;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payout events settling a withdrawal over a real broker and database, published as the contract's golden samples:
 * only a return moves money, once, and the status only rises.
 */
class PayoutReturnIT extends WalletIntegrationTest {
    @Autowired
    private PaymentEventListener listener;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void twoReturnsForOneWithdrawalAtOnceCreditTheMoneyBackOnce() {
        // Guards money created by a duplicate return (ADR 0033): two returns under different event ids, handled at
        // the same moment, must credit the withdrawal's amount back once and record the other as a duplicate.
        String userId = newUserId();
        String key = pendingWithdrawal(userId);

        Concurrently.run(2, thread -> {
            listener.onPaymentEvent(record(KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED, returned(key, userId)));
            return null;
        });

        assertThat(balance(userId)).isEqualByComparingTo("30.00");
        assertThat(ledgerTypes(key)).containsExactly("WITHDRAWAL", "WITHDRAWAL_RETURNED");
        assertThat(withdrawalStatus(userId, key)).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE transaction_reference = ? "
                        + "AND rejection_reason = 'DUPLICATE_REFERENCE'",
                Integer.class,
                key
        )).isOne();
    }

    @Test
    void aPaidWithdrawalThatFailsAndComesBackRisesToReturnedAndIsCreditedOnce() {
        // Guards the events of a payout that is paid, then fails at the bank and is returned: Paid and Failed move no
        // money and only raise the status, and the return credits the amount once (ADR 0033).
        String userId = newUserId();
        String key = pendingWithdrawal(userId);

        RecordMetadata paid = publish(KafkaConstants.EVENT_TYPE_PAYOUT_PAID, WireSample.of("payout-paid")
                .with("eventId", UUID.randomUUID().toString())
                .with("transactionReference", key)
                .with("userId", userId)
                .json());
        kafkaProbe.awaitConsumed(consumerGroup, paid);
        assertThat(withdrawalStatus(userId, key)).isEqualTo("PAID");
        assertThat(balance(userId)).isEqualByComparingTo("20.00");

        RecordMetadata failed = publish(KafkaConstants.EVENT_TYPE_PAYOUT_FAILED, WireSample.of("payout-failed")
                .with("eventId", UUID.randomUUID().toString())
                .with("transactionReference", key)
                .with("userId", userId)
                .with("providerPayoutId", "po_wire_sample")
                .with("providerFailureCode", "account_closed")
                .json());
        kafkaProbe.awaitConsumed(consumerGroup, failed);
        assertThat(withdrawalStatus(userId, key)).isEqualTo("RETURN_PENDING");
        assertThat(balance(userId)).isEqualByComparingTo("20.00");

        RecordMetadata returned = publish(KafkaConstants.EVENT_TYPE_PAYOUT_RETURNED, returned(key, userId));
        kafkaProbe.awaitConsumed(consumerGroup, returned);

        assertThat(withdrawalStatus(userId, key)).isEqualTo("RETURNED");
        assertThat(balance(userId)).isEqualByComparingTo("30.00");
        assertThat(ledgerTypes(key)).containsExactly("WITHDRAWAL", "WITHDRAWAL_RETURNED");
    }

    /**
     * Opens a wallet with 30.00 and withdraws 10.00 from it; returns the withdrawal's key.
     */
    private String pendingWithdrawal(String userId) {
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("30.00"));
        bankAccountReady();
        String key = newKey();
        assertThat(withdraw(userId, "10.00", key).getStatus().value()).isEqualTo(202);
        return key;
    }

    private static String returned(String key, String userId) {
        return WireSample.of("payout-returned")
                .with("eventId", UUID.randomUUID().toString())
                .with("transactionReference", key)
                .with("userId", userId)
                .json();
    }

    private RecordMetadata publish(String eventType, String payload) {
        String key = json.readTree(payload).get("transactionReference").asString();
        return kafkaProbe.publish(KafkaConstants.PAYMENT_EVENTS_TOPIC, key, eventType, payload);
    }

    private static ConsumerRecord<String, String> record(String eventType, String payload) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(KafkaConstants.PAYMENT_EVENTS_TOPIC, 0, 0L, "key", payload);
        record.headers().add(KafkaConstants.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private BigDecimal balance(String userId) {
        return jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE user_id = ? AND currency = 'PLN'",
                BigDecimal.class,
                userId
        );
    }

    private List<String> ledgerTypes(String key) {
        return jdbc.queryForList(
                "SELECT type FROM balance_history WHERE transaction_reference = ? ORDER BY entry_no",
                String.class,
                key
        );
    }

    private String withdrawalStatus(String userId, String key) {
        String status = http.get()
                .uri("/api/wallets/PLN/withdrawals/" + key)
                .header("X-User-Id", userId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return json.readTree(status).get("status").asString();
    }
}
