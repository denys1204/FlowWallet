package com.flowwallet.wallet.balance;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.support.Concurrently;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import com.flowwallet.wallet.support.WireSample;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer's barriers under real concurrency. The listener is called directly from several threads at once, as
 * consumers in a rebalance or two instances might: Kafka hands one partition to one thread, so only a direct call
 * reaches the race the unique indexes have to settle.
 */
class PaymentEventRaceIT extends WalletIntegrationTest {
    @Autowired
    private PaymentEventListener listener;

    @Test
    void oneEventHandledByEightThreadsAtOnceCreditsOnce() {
        // Guards ADR 0007's trap under concurrency: the losers wait on the winner's unique event_id, fail when it
        // commits, and must classify the violation from a fresh transaction rather than the aborted one, or a thread
        // throws and the record would be retried into the dead-letter topic.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();
        ConsumerRecord<String, String> record = completed(UUID.randomUUID().toString(), reference, userId);

        Concurrently.run(8, thread -> {
            listener.onPaymentEvent(record);
            return null;
        });

        assertThat(balance(userId)).isEqualByComparingTo("10.00");
        assertThat(count("SELECT count(*) FROM balance_history WHERE transaction_reference = ?", reference)).isOne();
        assertThat(outcomes(reference)).containsExactly("CREDITED");
    }

    @Test
    void sixEventsForOneReferenceAtOnceCreditOnceAndRecordTheRestAsDuplicates() {
        // Guards the second barrier under concurrency: six event ids pass the event_id index, queue on the wallet's
        // row lock, and only the unique (reference, type) in the ledger stops five more credits. Each loser must be
        // stored as DUPLICATE_REFERENCE, not lost and not credited.
        String userId = newUserId();
        openWallet(userId, "PLN");
        String reference = newKey();

        Concurrently.run(6, thread -> {
            listener.onPaymentEvent(completed(UUID.randomUUID().toString(), reference, userId));
            return null;
        });

        assertThat(balance(userId)).isEqualByComparingTo("10.00");
        assertThat(count("SELECT count(*) FROM balance_history WHERE transaction_reference = ?", reference)).isOne();
        assertThat(outcomes(reference)).containsExactlyInAnyOrder(
                "CREDITED", "REJECTED", "REJECTED", "REJECTED", "REJECTED", "REJECTED"
        );
        assertThat(count(
                "SELECT count(*) FROM processed_events WHERE transaction_reference = ? "
                        + "AND rejection_reason = 'DUPLICATE_REFERENCE'",
                reference
        )).isEqualTo(5);
    }

    @Test
    void eightPaymentsToOneWalletAtOnceAreAllCredited() {
        // Guards a lost update on the balance: eight different payments pass both unique indexes, so only the wallet's
        // row lock orders them. Without it two threads read the same balance and entry number, and one credit is lost
        // or fails on the version or the (wallet_id, entry_no) index. LedgerAudit then checks the entries add up.
        String userId = newUserId();
        openWallet(userId, "PLN");
        List<String> references = Concurrently.run(8, thread -> {
            String reference = newKey();
            listener.onPaymentEvent(completed(UUID.randomUUID().toString(), reference, userId));
            return reference;
        });

        assertThat(balance(userId)).isEqualByComparingTo("80.00");
        assertThat(references).allSatisfy(reference -> assertThat(outcomes(reference)).containsExactly("CREDITED"));
        assertThat(jdbc.queryForObject(
                "SELECT last_entry_no FROM wallets WHERE user_id = ? AND currency = 'PLN'",
                Long.class,
                userId
        )).isEqualTo(8);
    }

    private static ConsumerRecord<String, String> completed(String eventId, String reference, String userId) {
        String json = WireSample.of("payment-completed")
                .with("eventId", eventId)
                .with("transactionReference", reference)
                .with("userId", userId)
                .json();
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(KafkaConstants.PAYMENT_EVENTS_TOPIC, 0, 0L, reference, json);
        record.headers().add(
                KafkaConstants.HEADER_EVENT_TYPE,
                KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED.getBytes(StandardCharsets.UTF_8)
        );
        return record;
    }

    private BigDecimal balance(String userId) {
        return jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE user_id = ? AND currency = 'PLN'",
                BigDecimal.class,
                userId
        );
    }

    private int count(String sql, String reference) {
        return jdbc.queryForObject(sql, Integer.class, reference);
    }

    private List<String> outcomes(String reference) {
        return jdbc.queryForList(
                "SELECT outcome FROM processed_events WHERE transaction_reference = ? ORDER BY id",
                String.class,
                reference
        );
    }
}
