package com.flowwallet.wallet.withdrawal;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.wallet.support.DatabaseFaults;
import com.flowwallet.wallet.support.PaymentStub;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import com.flowwallet.wallet.support.WireSample;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A withdrawal on the wallet's side, over HTTP into the service: the preflight call, the debit, the PENDING row and the
 * command committed together, the command as it reaches wallet.withdrawals, and the refusals that write nothing or
 * spend the key.
 */
class WithdrawalFlowIT extends WalletIntegrationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aWithdrawalDebitsTheWalletAndSendsItsCommandFromTheSameCommit() {
        // Guards the withdrawal's one transaction (ADR 0033, ADR 0034): the debit, the PENDING row and the outbox row
        // commit together, the command reaches wallet.withdrawals in the shape of its golden sample, and the receipt's
        // Location answers with the withdrawal's status.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("30.00"));
        bankAccountReady();
        String key = newKey();
        Map<TopicPartition, Long> start = kafkaProbe.endOffsets(KafkaConstants.WALLET_WITHDRAWALS_TOPIC);

        EntityExchangeResult<String> answer = withdraw(userId, "10.00", key);

        assertThat(answer.getStatus().value()).isEqualTo(202);
        assertThat(answer.getResponseHeaders().getLocation()).hasToString("/api/wallets/PLN/withdrawals/" + key);
        JsonNode receipt = json.readTree(answer.getResponseBody());
        assertThat(receipt.get("reference").asString()).isEqualTo(key);
        assertThat(receipt.get("balanceAfter").asString()).isEqualTo("20.00");
        assertThat(balance(userId)).isEqualByComparingTo("20.00");
        assertThat(ledgerTypes(key)).containsExactly("WITHDRAWAL");
        assertThat(withdrawalStatus(key)).isEqualTo("PENDING");
        assertThat(outboxRows(key)).isOne();

        PaymentStub.Request preflight = paymentStub.requests(PREFLIGHT_PATH).getFirst();
        assertThat(preflight.header("X-User-Id")).isEqualTo(userId);
        assertThat(json.readTree(preflight.body()).get("amount").decimalValue()).isEqualByComparingTo("10.00");

        ConsumerRecord<String, String> command = kafkaProbe.awaitRecord(start, r -> key.equals(r.key()));
        assertThat(new String(command.headers().lastHeader(KafkaConstants.HEADER_EVENT_TYPE).value(),
                StandardCharsets.UTF_8)).isEqualTo(KafkaConstants.EVENT_TYPE_WITHDRAWAL_REQUESTED);
        WireSample.of("withdrawal-requested").assertSameShape(command.value());
        JsonNode event = json.readTree(command.value());
        assertThat(event.get("transactionReference").asString()).isEqualTo(key);
        assertThat(event.get("userId").asString()).isEqualTo(userId);
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(event.get("currency").asString()).isEqualTo("PLN");

        String status = http.get()
                .uri("/api/wallets/PLN/withdrawals/" + key)
                .header("X-User-Id", userId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(json.readTree(status).get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void aWithdrawalWhoseCommandCannotBeWrittenDebitsNothingAndItsRetryGoesThroughOnce() {
        // Guards a debit with no command behind it, money that would leave the wallet and never reach a bank: when the
        // outbox row fails, the debit and the PENDING row roll back with it, and the retry under the same key
        // withdraws once.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("30.00"));
        bankAccountReady();
        String key = newKey();

        try (var fault = DatabaseFaults.failInsert(jdbc, "outbox_events", "aggregate_id", key)) {
            assertThat(withdraw(userId, "10.00", key).getStatus().is5xxServerError()).isTrue();

            assertThat(balance(userId)).isEqualByComparingTo("30.00");
            assertThat(ledgerTypes(key)).isEmpty();
            assertThat(withdrawalStatus(key)).isNull();
            assertThat(outboxRows(key)).isZero();
        }

        assertThat(withdraw(userId, "10.00", key).getStatus().value()).isEqualTo(202);

        assertThat(balance(userId)).isEqualByComparingTo("20.00");
        assertThat(ledgerTypes(key)).containsExactly("WITHDRAWAL");
        assertThat(outboxRows(key)).isOne();
    }

    @Test
    void aWithdrawalWhoseOwnRowCannotBeWrittenSendsNoCommand() {
        // Guards a command committed apart from its debit, for example from a transaction of its own: the withdrawal
        // row is written at the flush, after the command was queued, and when it fails the command must roll back
        // with it, or Payment Service would pay out money that never left the wallet.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("30.00"));
        bankAccountReady();
        String key = newKey();

        try (var fault = DatabaseFaults.failInsert(jdbc, "withdrawals", "transaction_reference", key)) {
            assertThat(withdraw(userId, "10.00", key).getStatus().is5xxServerError()).isTrue();
        }

        assertThat(balance(userId)).isEqualByComparingTo("30.00");
        assertThat(ledgerTypes(key)).isEmpty();
        assertThat(outboxRows(key)).isZero();
    }

    @Test
    void aWithdrawalWithoutABankAccountIsRefusedUnderItsKeyAndTheSameRefusalIsRepeated() {
        // Guards the one refusal that spends a key (ADR 0033): it is recorded as REFUSED with nothing debited, and a
        // retry gets the same 422 (the problem's timestamp aside) rather than a withdrawal that goes through once the
        // account is set.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("30.00"));
        paymentStub.answer(PREFLIGHT_PATH, 200, "{\"bankAccount\": \"NOT_SET\", \"reserveSufficient\": null}");
        String key = newKey();

        EntityExchangeResult<String> first = withdraw(userId, "10.00", key);
        bankAccountReady();
        EntityExchangeResult<String> retry = withdraw(userId, "10.00", key);

        assertThat(first.getStatus().value()).isEqualTo(422);
        assertThat(retry.getStatus().value()).isEqualTo(422);
        assertThat(detail(retry)).isEqualTo(detail(first)).contains("spent");
        assertThat(withdrawalStatus(key)).isEqualTo("REFUSED");
        assertThat(balance(userId)).isEqualByComparingTo("30.00");
        assertThat(ledgerTypes(key)).isEmpty();
        assertThat(outboxRows(key)).isZero();
    }

    @Test
    void aShortPlatformReserveWritesNothingAndLeavesTheKeyFree() {
        // Guards a withdrawal debited while the platform cannot pay it out: a short reserve answers 503 with nothing
        // written, so the same key can go through once the reserve is topped up.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("30.00"));
        paymentStub.answer(PREFLIGHT_PATH, 200, "{\"bankAccount\": \"READY\", \"reserveSufficient\": false}");
        String key = newKey();

        assertThat(withdraw(userId, "10.00", key).getStatus().value()).isEqualTo(503);

        assertThat(balance(userId)).isEqualByComparingTo("30.00");
        assertThat(withdrawalStatus(key)).isNull();
        bankAccountReady();
        assertThat(withdraw(userId, "10.00", key).getStatus().value()).isEqualTo(202);
    }

    private String detail(EntityExchangeResult<String> answer) {
        return json.readTree(answer.getResponseBody()).get("detail").asString();
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

    private String withdrawalStatus(String key) {
        return jdbc.queryForList(
                "SELECT status FROM withdrawals WHERE transaction_reference = ?",
                String.class,
                key
        ).stream().findFirst().orElse(null);
    }

    private int outboxRows(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                key
        );
    }
}
