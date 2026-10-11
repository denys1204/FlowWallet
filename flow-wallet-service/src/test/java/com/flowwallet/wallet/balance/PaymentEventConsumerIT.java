package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.config.KafkaConsumerConfig;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import com.flowwallet.wallet.support.WireSample;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The payment event consumer over a real broker and database: the records are the contract's golden samples, so the
 * JSON is what Payment Service sends, and the barriers are the real unique indexes.
 */
class PaymentEventConsumerIT extends WalletIntegrationTest {
    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

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

    @Test
    void aSecondEventForACreditedReferenceIsRefusedAsADuplicate() {
        // Guards two credits for one payment, as when the webhook and the reconciler both settle it under different
        // event ids: the unique (reference, type) in the ledger refuses the second, and the refusal is stored.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();

        publishCompleted(UUID.randomUUID().toString(), reference, userId, null);
        RecordMetadata second = publishCompleted(UUID.randomUUID().toString(), reference, userId, null);
        kafkaProbe.awaitConsumed(consumerGroup, second);

        assertThat(balance(userId, "PLN")).isEqualByComparingTo("10.00");
        assertThat(ledgerTypes(reference)).containsExactly("DEPOSIT");
        assertThat(outcomes(reference)).containsExactly("CREDITED", "REJECTED");
        assertThat(rejectionReasons(reference)).containsExactly("DUPLICATE_REFERENCE");
    }

    @Test
    void aPaymentForAUserWithoutAWalletIsStoredAsRefusedAndOpensNoWallet() {
        // Guards a wallet created as a side effect of a payment (ADR 0004), and a refusal stored without the payload
        // an operator needs to republish it once the wallet exists.
        String userId = newUserId();
        String reference = newKey();

        RecordMetadata record = publishCompleted(UUID.randomUUID().toString(), reference, userId, null);
        kafkaProbe.awaitConsumed(consumerGroup, record);

        assertThat(outcomes(reference)).containsExactly("REJECTED");
        assertThat(rejectionReasons(reference)).containsExactly("WALLET_NOT_FOUND");
        assertThat(jdbc.queryForObject(
                "SELECT payload FROM processed_events WHERE transaction_reference = ?",
                String.class,
                reference
        )).contains(reference);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    void anUnreadableRecordIsDeadLetteredAndTheNextPaymentOnItsPartitionStillCredits() {
        // Guards a poison record that blocks its partition, is retried forever, or is dropped: it goes to the
        // dead-letter topic, is counted, and the topic keeps it without a time limit (ADR 0020). That it skips the
        // retries is pinned by KafkaConsumerConfigTest; three short retries would pass here too.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();
        Map<TopicPartition, Long> deadLetters = kafkaProbe.endOffsets(KafkaConsumerConfig.DEAD_LETTER_TOPIC);
        double counted = deadLettersCounted();

        kafkaProbe.publish(
                KafkaConstants.PAYMENT_EVENTS_TOPIC,
                reference,
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED,
                "{not json"
        );
        RecordMetadata next = publishCompleted(UUID.randomUUID().toString(), reference, userId, null);
        kafkaProbe.awaitConsumed(consumerGroup, next);

        assertThat(balance(userId, "PLN")).isEqualByComparingTo("10.00");
        assertThat(kafkaProbe.recordsSince(deadLetters)).singleElement().satisfies(letter -> {
            assertThat(letter.key()).isEqualTo(reference);
            assertThat(letter.value()).isEqualTo("{not json");
        });
        assertThat(deadLettersCounted()).isEqualTo(counted + 1);
        assertThat(kafkaProbe.topicConfig(KafkaConsumerConfig.DEAD_LETTER_TOPIC, "retention.ms")).isEqualTo("-1");
    }

    @Test
    void anUnparseableRecordIsDeadLetteredWithNothingOfItInTheExceptionHeaders() {
        // Guards a user id in the dead letter's exception headers (ADR 0044): the stack trace header carries the cause
        // chain, and Jackson's message quotes the record. The value itself stays as it came, for an operator.
        String userId = newUserId();
        String reference = newKey();
        Map<TopicPartition, Long> deadLetters = kafkaProbe.endOffsets(KafkaConsumerConfig.DEAD_LETTER_TOPIC);
        String json = WireSample.of("payment-completed")
                .with("eventId", UUID.randomUUID().toString())
                .with("transactionReference", reference)
                .with("userId", userId)
                .with("amount", userId)
                .json();

        kafkaProbe.publish(
                KafkaConstants.PAYMENT_EVENTS_TOPIC,
                reference,
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED,
                json
        );

        ConsumerRecord<String, String> letter = kafkaProbe.awaitRecord(deadLetters, r -> reference.equals(r.key()));
        assertThat(letter.value()).isEqualTo(json);
        assertThat(header(letter, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .contains("at line 1, column")
                .doesNotContain(userId);
        assertThat(header(letter, KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).doesNotContain(userId);
    }

    @Test
    void theKafkaTemplateSendsThroughTheListenerThatLeavesValuesOut() {
        // Guards Spring Boot's own producer listener, which logs the start of a value that fails to send: it backs off
        // only while a ProducerListener bean of the service's exists (ADR 0044).
        Object producerListener = ReflectionTestUtils.getField(kafkaTemplate, "producerListener");

        assertThat(producerListener).isInstanceOf(LoggingProducerListener.class);
        assertThat(ReflectionTestUtils.getField(producerListener, "includeContents")).isEqualTo(false);
    }

    @Test
    void aRecordWithAnUnknownEventTypeIsDeadLetteredRatherThanGuessedFromItsJson() {
        // Guards dispatch on the shape of the JSON: a completed payment's payload under a header the wallet does not
        // know must not credit anything.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();
        Map<TopicPartition, Long> deadLetters = kafkaProbe.endOffsets(KafkaConsumerConfig.DEAD_LETTER_TOPIC);
        String json = WireSample.of("payment-completed")
                .with("eventId", UUID.randomUUID().toString())
                .with("transactionReference", reference)
                .with("userId", userId)
                .json();

        RecordMetadata record = kafkaProbe.publish(
                KafkaConstants.PAYMENT_EVENTS_TOPIC,
                reference,
                "PaymentRefundedEvent",
                json
        );
        kafkaProbe.awaitConsumed(consumerGroup, record);

        assertThat(balance(userId, "PLN")).isEqualByComparingTo("0");
        assertThat(outcomes(reference)).isEmpty();
        assertThat(kafkaProbe.recordsSince(deadLetters)).singleElement()
                .satisfies(letter -> assertThat(letter.value()).isEqualTo(json));
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

    private List<String> rejectionReasons(String reference) {
        return jdbc.queryForList(
                "SELECT rejection_reason FROM processed_events WHERE transaction_reference = ? "
                        + "AND rejection_reason IS NOT NULL ORDER BY id",
                String.class,
                reference
        );
    }

    private double deadLettersCounted() {
        Counter counter = meterRegistry.find("wallet.consumer.dead.letters").counter();
        return counter == null ? 0 : counter.count();
    }

    private List<String> outcomes(String reference) {
        return jdbc.queryForList(
                "SELECT outcome FROM processed_events WHERE transaction_reference = ? ORDER BY id",
                String.class,
                reference
        );
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
