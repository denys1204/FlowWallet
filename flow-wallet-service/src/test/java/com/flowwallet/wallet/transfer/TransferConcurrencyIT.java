package com.flowwallet.wallet.transfer;

import com.flowwallet.wallet.support.Concurrently;
import com.flowwallet.wallet.support.DatabaseFaults;
import com.flowwallet.wallet.support.WalletIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfers under real concurrency, over HTTP into the running service: the lock order, the unique key and the
 * overdraft check are decided by Postgres here, where the unit tests can only mock them.
 */
class TransferConcurrencyIT extends WalletIntegrationTest {
    private static final BigDecimal SEED = new BigDecimal("1000.00");

    @Test
    void transfersInBothDirectionsAndAroundARingNeitherDeadlockNorLoseMoney() {
        // Guards the lock order of ADR 0011: opposite transfers that locked their sender first would deadlock, and a
        // ring of three would deadlock even with pairwise care. Every transfer must succeed, move its money once, and
        // leave each balance at its seed plus what came in minus what went out.
        String a = fundedWallet();
        String b = fundedWallet();
        String c = fundedWallet();
        String d = fundedWallet();
        String e = fundedWallet();
        List<Planned> plan = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            plan.add(new Planned(a, b, newKey()));
            plan.add(new Planned(b, a, newKey()));
        }
        for (int i = 0; i < 100; i++) {
            plan.add(new Planned(c, d, newKey()));
            plan.add(new Planned(d, e, newKey()));
            plan.add(new Planned(e, c, newKey()));
        }

        List<List<Integer>> statuses = Concurrently.run(16, thread -> {
            List<Integer> mine = new ArrayList<>();
            for (int i = thread; i < plan.size(); i += 16) {
                Planned transfer = plan.get(i);
                mine.add(transfer(transfer.from(), transfer.to(), "1.00", transfer.key()).getStatus().value());
            }
            return mine;
        });

        assertThat(statuses).flatMap(list -> list).containsOnly(200).hasSize(700);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT transaction_reference
                    FROM balance_history
                    WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')
                      AND wallet_id IN (SELECT id FROM wallets WHERE user_id IN (?, ?, ?, ?, ?))
                    GROUP BY transaction_reference
                    HAVING count(*) FILTER (WHERE type = 'TRANSFER_OUT') = 1
                       AND count(*) FILTER (WHERE type = 'TRANSFER_IN') = 1
                       AND count(*) = 2
                ) paired
                """, Integer.class, a, b, c, d, e)).isEqualTo(700);
        for (String user : List.of(a, b, c, d, e)) {
            assertThat(balance(user)).isEqualByComparingTo(SEED);
        }
    }

    @Test
    void twentyRequestsWithOneKeyMoveTheMoneyOnceAndAllGetTheSameAnswer() {
        // Guards a double transfer when a client retries in parallel, and a key judged before the locks: the sender
        // holds exactly the amount, so a request that read no key before the winner committed would try to debit an
        // empty wallet and answer 422. Under the locks every other request gets the first answer, byte for byte.
        String sender = newUserId();
        openWallet(sender, "PLN");
        fund(sender, "PLN", new BigDecimal("10.00"));
        String recipient = fundedWallet();
        String key = newKey();

        List<EntityExchangeResult<String>> answers = Concurrently.run(20, thread ->
                transfer(sender, recipient, "10.00", key));

        assertThat(answers).allSatisfy(answer -> assertThat(answer.getStatus().value()).isEqualTo(200));
        assertThat(answers).extracting(EntityExchangeResult::getResponseBody).containsOnly(
                answers.getFirst().getResponseBody()
        );
        assertThat(legs(key)).isEqualTo(2);
        assertThat(balance(sender)).isEqualByComparingTo("0");
        assertThat(balance(recipient)).isEqualByComparingTo("1010.00");
    }

    @Test
    void oneKeyRacedFromTwoSendersWithNoWalletInCommonGoesToOneOfThem() {
        // Guards a key used by two senders whose transfers share no lock: only the unique index on the reference can
        // stop both from moving money, and the loser must get 409, not a 500. The ledger insert under this key waits
        // half a second, so both transactions pass the key check before either commits and the index decides.
        String key = newKey();
        String[] senders = {fundedWallet(), fundedWallet()};
        String[] recipients = {fundedWallet(), fundedWallet()};

        List<Integer> statuses;
        try (var delay = DatabaseFaults.delayInsert(jdbc, "balance_history", "transaction_reference", key, 0.5)) {
            statuses = Concurrently.run(2, thread ->
                    transfer(senders[thread], recipients[thread], "10.00", key).getStatus().value());
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(legs(key)).isEqualTo(2);
    }

    @Test
    void oneKeyRacedFromTwoSendersToOneRecipientGoesToOneOfThem() {
        // Guards the same race when the two transfers queue on a shared wallet's lock: one moves the money, the other
        // gets 409, and the recipient is credited once.
        String key = newKey();
        String[] senders = {fundedWallet(), fundedWallet()};
        String recipient = fundedWallet();

        List<Integer> statuses = Concurrently.run(2, thread ->
                transfer(senders[thread], recipient, "10.00", key).getStatus().value());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(legs(key)).isEqualTo(2);
        assertThat(balance(recipient)).isEqualByComparingTo("1010.00");
    }

    @Test
    void twoTransfersThatTogetherOverdrawTheWalletLetOnlyOneThroughAndItsReplayStaysTheSame() {
        // Guards an overdraft by two requests that each fit the balance alone, and a replay that rebuilds the receipt
        // from today's balance instead of returning the first answer: the rest of the balance is spent before the
        // replay, so a rebuilt receipt would show 0.00 rather than 5.00.
        String sender = newUserId();
        openWallet(sender, "PLN");
        fund(sender, "PLN", new BigDecimal("30.00"));
        String recipient = fundedWallet();
        String[] keys = {newKey(), newKey()};

        List<EntityExchangeResult<String>> answers = Concurrently.run(2, thread ->
                transfer(sender, recipient, "25.00", keys[thread]));

        assertThat(answers).extracting(answer -> answer.getStatus().value()).containsExactlyInAnyOrder(200, 422);
        assertThat(balance(sender)).isEqualByComparingTo("5.00");
        assertThat(transfer(sender, recipient, "5.00", newKey()).getStatus().value()).isEqualTo(200);
        int winner = answers.get(0).getStatus().value() == 200 ? 0 : 1;
        EntityExchangeResult<String> replay = transfer(sender, recipient, "25.00", keys[winner]);
        assertThat(replay.getStatus().value()).isEqualTo(200);
        assertThat(replay.getResponseBody()).isEqualTo(answers.get(winner).getResponseBody());
    }

    private String fundedWallet() {
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", SEED);
        return userId;
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

    private BigDecimal balance(String userId) {
        return jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE user_id = ? AND currency = 'PLN'",
                BigDecimal.class,
                userId
        );
    }

    private int legs(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM balance_history WHERE transaction_reference = ?",
                Integer.class,
                key
        );
    }

    private record Planned(String from, String to, String key) {
    }
}
