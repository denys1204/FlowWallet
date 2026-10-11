package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.config.KafkaConsumerConfig;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import com.flowwallet.wallet.support.WireSample;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The payment event consumer over a real broker and database: the records are the contract's golden samples, so the
 * JSON is what Payment Service sends, and the barriers are the real unique indexes.
 */
class PaymentEventConsumerIT extends WalletIntegrationTest {
    @Test
    void aCompletedPaymentCreditsTheWalletOnce() {
        // Guards the whole consumer path the unit tests mock: the header dispatch, the JSON as Payment Service writes
        // it (amount 10.0000 from the sample), and the barrier row and the credit both written.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();

        publishCompleted(UUID.randomUUID().toString(), reference, userId, null);

        await().untilAsserted(() -> assertThat(balance(userId, "PLN")).isEqualByComparingTo("10.00"));
        assertThat(ledgerTypes(reference)).containsExactly("DEPOSIT");
        assertThat(outcomes(reference)).containsExactly("CREDITED");
    }

    @Test
    void theSameEventDeliveredAgainChangesNothing() {
        // Guards a double credit on redelivery, the main promise of the consumer: Kafka delivers at least once. Both
        // the unique event_id and the unique (reference, type) in the ledger stop the second credit, and a redelivery
        // must be acknowledged quietly rather than retried into the dead-letter topic.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();
        String eventId = UUID.randomUUID().toString();
        long deadLettersBefore = kafkaProbe.recordCount(KafkaConsumerConfig.DEAD_LETTER_TOPIC);

        publishCompleted(eventId, reference, userId, new BigDecimal("25.00"));
        RecordMetadata redelivery = publishCompleted(eventId, reference, userId, new BigDecimal("25.00"));
        kafkaProbe.awaitConsumed(consumerGroup, redelivery);

        assertThat(balance(userId, "PLN")).isEqualByComparingTo("25.00");
        assertThat(ledgerTypes(reference)).containsExactly("DEPOSIT");
        assertThat(outcomes(reference)).containsExactly("CREDITED");
        assertThat(kafkaProbe.recordCount(KafkaConsumerConfig.DEAD_LETTER_TOPIC)).isEqualTo(deadLettersBefore);
    }

    @Test
    void aFailedEventDeliveredAgainIsRecordedOnce() {
        // Guards the unique event_id where it is the only barrier: a failure writes no ledger row, so without the
        // index a redelivered failure would be recorded twice, and its retry would end in the dead-letter topic.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();
        String eventId = UUID.randomUUID().toString();
        long deadLettersBefore = kafkaProbe.recordCount(KafkaConsumerConfig.DEAD_LETTER_TOPIC);

        publishFailed(eventId, reference, userId);
        RecordMetadata redelivery = publishFailed(eventId, reference, userId);
        kafkaProbe.awaitConsumed(consumerGroup, redelivery);

        assertThat(outcomes(reference)).containsExactly("FAILURE_RECORDED");
        assertThat(balance(userId, "PLN")).isEqualByComparingTo("0");
        assertThat(kafkaProbe.recordCount(KafkaConsumerConfig.DEAD_LETTER_TOPIC)).isEqualTo(deadLettersBefore);
    }

    @Test
    void aFailedPaymentRetriedToSuccessIsCreditedOnce() {
        // Guards the retried payment: failed, then completed for the same reference. The failure moves no money and
        // must not block the later credit.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();

        publishFailed(UUID.randomUUID().toString(), reference, userId);
        RecordMetadata completed = publishCompleted(UUID.randomUUID().toString(), reference, userId, null);
        kafkaProbe.awaitConsumed(consumerGroup, completed);

        assertThat(balance(userId, "PLN")).isEqualByComparingTo("10.00");
        assertThat(ledgerTypes(reference)).containsExactly("DEPOSIT");
        assertThat(outcomes(reference)).containsExactlyInAnyOrder("FAILURE_RECORDED", "CREDITED");
    }

    @Test
    void aFailureArrivingAfterTheCreditTakesNothingBack() {
        // Guards a consumer that depends on event order: a failure that arrives after the credit, for the same
        // reference, moves no money (ADR 0009).
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();

        publishCompleted(UUID.randomUUID().toString(), reference, userId, null);
        RecordMetadata failed = publishFailed(UUID.randomUUID().toString(), reference, userId);
        kafkaProbe.awaitConsumed(consumerGroup, failed);

        assertThat(balance(userId, "PLN")).isEqualByComparingTo("10.00");
        assertThat(ledgerTypes(reference)).containsExactly("DEPOSIT");
        assertThat(outcomes(reference)).containsExactlyInAnyOrder("CREDITED", "FAILURE_RECORDED");
    }

    /**
     * @param amount the amount to send, or null to send the sample's own 10.0000
     */
    private RecordMetadata publishCompleted(String eventId, String reference, String userId, BigDecimal amount) {
        WireSample sample = WireSample.of("payment-completed")
                .with("eventId", eventId)
                .with("transactionReference", reference)
                .with("userId", userId);
        if (amount != null) {
            sample.with("amount", amount);
        }
        return kafkaProbe.publish(
                KafkaConstants.PAYMENT_EVENTS_TOPIC,
                reference,
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED,
                sample.json()
        );
    }

    private RecordMetadata publishFailed(String eventId, String reference, String userId) {
        String json = WireSample.of("payment-failed")
                .with("eventId", eventId)
                .with("transactionReference", reference)
                .with("userId", userId)
                .json();
        return kafkaProbe.publish(
                KafkaConstants.PAYMENT_EVENTS_TOPIC,
                reference,
                KafkaConstants.EVENT_TYPE_PAYMENT_FAILED,
                json
        );
    }

    private BigDecimal balance(String userId, String currency) {
        return jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE user_id = ? AND currency = ?",
                BigDecimal.class,
                userId,
                currency
        );
    }

    private List<String> ledgerTypes(String reference) {
        return jdbc.queryForList(
                "SELECT type FROM balance_history WHERE transaction_reference = ? ORDER BY entry_no",
                String.class,
                reference
        );
    }

    private List<String> outcomes(String reference) {
        return jdbc.queryForList(
                "SELECT outcome FROM processed_events WHERE transaction_reference = ? ORDER BY id",
                String.class,
                reference
        );
    }
}
