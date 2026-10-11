package com.flowwallet.wallet.withdrawal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.wallet.support.Concurrently;
import com.flowwallet.wallet.support.DatabaseFaults;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Withdrawals under real concurrency, over HTTP into the service: the key is judged under the wallet's lock, the
 * unique reference settles a key raced from two wallets without sending the loser's command, and withdrawals and
 * transfers share one lock and one balance.
 */
class WithdrawalConcurrencyIT extends WalletIntegrationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void twentyRequestsWithOneKeyDebitOnceSendOneCommandAndAllGetTheSameAnswer() {
        // Guards a second debit or a second command when a client retries in parallel, and a key judged before the
        // wallet's lock: under the lock every repeat finds the winner's withdrawal, so none may reach the unique
        // reference, which would answer the same 202 but log that the lock did not serialize it.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("10.00"));
        bankAccountReady();
        String key = newKey();
        Logger serviceLog = (Logger) LoggerFactory.getLogger(WithdrawalService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        serviceLog.addAppender(logs);

        List<EntityExchangeResult<String>> answers;
        try {
            answers = Concurrently.run(20, thread -> withdraw(userId, "10.00", key));
        } finally {
            serviceLog.detachAppender(logs);
        }

        assertThat(answers).allSatisfy(answer -> assertThat(answer.getStatus().value()).isEqualTo(202));
        assertThat(answers).extracting(EntityExchangeResult::getResponseBody)
                .containsOnly(answers.getFirst().getResponseBody());
        assertThat(count("SELECT count(*) FROM balance_history WHERE transaction_reference = ?", key)).isOne();
        assertThat(count("SELECT count(*) FROM withdrawals WHERE transaction_reference = ?", key)).isOne();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", key)).isOne();
        assertThat(balance(userId)).isEqualByComparingTo("0");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("reached the unique index although"));
    }

    @Test
    void oneKeyRacedFromTwoWalletsGoesToOneOfThem() {
        // Guards a key that debits two users: the two withdrawals share no lock, so the unique reference decides, and
        // the loser gets 409 with nothing debited and no command sent. The ledger insert under this key waits half a
        // second, so both requests pass the key check and the index, not the check, decides.
        String key = newKey();
        String[] users = {newUserId(), newUserId()};
        for (String user : users) {
            openWallet(user, "PLN");
            fund(user, "PLN", new BigDecimal("30.00"));
        }
        bankAccountReady();

        List<Integer> statuses;
        try (var delay = DatabaseFaults.delayInsert(jdbc, "balance_history", "transaction_reference", key, 0.5)) {
            statuses = Concurrently.run(2, thread -> withdraw(users[thread], "10.00", key).getStatus().value());
        }

        assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        assertThat(count("SELECT count(*) FROM balance_history WHERE transaction_reference = ?", key)).isOne();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", key)).isOne();
        assertThat(balance(users[0]).add(balance(users[1]))).isEqualByComparingTo("50.00");
    }

    @Test
    void twoWithdrawalsThatTogetherOverdrawTheWalletLetOneThroughAndRecordTheOtherAsRefused() {
        // Guards an overdraft by two withdrawals that each fit the balance alone: one is debited, the other is refused
        // for funds under its key, and its retry repeats the refusal (the problem's timestamp aside).
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("30.00"));
        bankAccountReady();
        String[] keys = {newKey(), newKey()};

        List<EntityExchangeResult<String>> answers = Concurrently.run(2, thread ->
                withdraw(userId, "25.00", keys[thread]));

        assertThat(answers).extracting(answer -> answer.getStatus().value()).containsExactlyInAnyOrder(202, 422);
        assertThat(balance(userId)).isEqualByComparingTo("5.00");
        int loser = answers.get(0).getStatus().value() == 422 ? 0 : 1;
        assertThat(jdbc.queryForObject(
                "SELECT refusal_reason FROM withdrawals WHERE transaction_reference = ?",
                String.class,
                keys[loser]
        )).isEqualTo("INSUFFICIENT_FUNDS");
        EntityExchangeResult<String> retry = withdraw(userId, "25.00", keys[loser]);
        assertThat(retry.getStatus().value()).isEqualTo(422);
        assertThat(detail(retry)).isEqualTo(detail(answers.get(loser)));
    }

    @Test
    void withdrawalsAndTransfersRacingForOneBalanceNeverOverdrawItOrFailForTheLock() {
        // Guards the shared lock between the two ways money leaves a wallet: forty requests of 5.00 against 100.00 let
        // exactly twenty through, refuse the rest for funds, and never answer 503 or 500 for a lock that waited. Each
        // thread alternates a transfer and a withdrawal, so withdrawals, slowed by their preflight call, still overlap
        // the transfers: no thread's second transfer starts before its first withdrawal, so the first withdrawal finds
        // at least half the balance left.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("100.00"));
        String recipient = newUserId();
        openWallet(recipient, "PLN");
        bankAccountReady();

        List<List<Integer>> perThread = Concurrently.run(10, thread -> List.of(
                transfer(userId, recipient, "5.00", newKey()).getStatus().value(),
                withdraw(userId, "5.00", newKey()).getStatus().value(),
                transfer(userId, recipient, "5.00", newKey()).getStatus().value(),
                withdraw(userId, "5.00", newKey()).getStatus().value()
        ));
        List<Integer> statuses = perThread.stream().flatMap(List::stream).toList();

        assertThat(statuses).containsOnly(200, 202, 422);
        assertThat(statuses.stream().filter(status -> status != 422)).hasSize(20);
        assertThat(balance(userId)).isEqualByComparingTo("0");
    }

    private EntityExchangeResult<String> transfer(String from, String to, String amount, String key) {
        return http.post()
                .uri("/api/wallets/PLN/transfers")
                .header("X-User-Id", from)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"to\": \"%s\", \"amount\": \"%s\"}".formatted(to, amount))
                .exchange()
                .expectBody(String.class)
                .returnResult();
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

    private int count(String sql, String key) {
        return jdbc.queryForObject(sql, Integer.class, key);
    }
}
