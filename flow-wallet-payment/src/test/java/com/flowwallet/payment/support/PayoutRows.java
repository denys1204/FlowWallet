package com.flowwallet.payment.support;

import com.flowwallet.contract.constant.KafkaConstants;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The payout tests' rows in payment_db: a user's ready payout account, a payout's columns, and the shifts that make a
 * deferred or claimed payout due again without waiting.
 * <p>
 * {@code PayoutExecutor.run()} takes every due payout, not only the test's: a row another test left due would reach
 * the Stripe tripwire, and its failures would count toward the executor's stop after three in a row. So the payout
 * tests are the one exception to the rule that no test truncates: {@link #clearOnceQuiet} empties {@code payouts},
 * the one table the executor takes its due rows from, once no command is in flight.
 */
public final class PayoutRows {
    private PayoutRows() {
    }

    /**
     * The Stripe ids of a payout account seeded as {@code READY} for PLN.
     */
    public record ReadyAccount(String stripeAccountId, String bankAccountId) {
    }

    /**
     * Waits until Payment Service's consumer group has finished every withdrawal command published so far, then
     * empties {@code payouts}. The consumer's acceptance is the only writer of the table that runs on its own thread:
     * the executor and the reconciler run only when a test calls them, and a webhook returns once it has written. The
     * outbox writes only {@code outbox_events}, which stays, and each test reads its events by its own reference.
     */
    public static void clearOnceQuiet(JdbcTemplate jdbc, KafkaProbe kafkaProbe, String consumerGroup) {
        kafkaProbe.awaitConsumedToEnd(consumerGroup, KafkaConstants.WALLET_WITHDRAWALS_TOPIC);
        jdbc.execute("TRUNCATE payouts");
    }

    /**
     * Seeds the user's payout account as Payment Service leaves it once a bank account is attached: a connected account
     * whose PLN bank account takes payouts. The id is taken from the table's sequence, which never hands the same value
     * to Hibernate.
     */
    public static ReadyAccount seedReadyAccount(JdbcTemplate jdbc, String userId) {
        ReadyAccount account = new ReadyAccount(StripeFixtures.fakeId("acct"), StripeFixtures.fakeId("ba"));
        jdbc.update(
                """
                        INSERT INTO payout_accounts (id, user_id, account_key, stripe_account_id, tos_accepted_at,
                            external_account_id, bank_last4, bank_currency, last_token, bank_state)
                        VALUES (nextval('payout_accounts_seq'), ?, ?, ?, LOCALTIMESTAMP, ?, '0000', 'PLN', ?, 'READY')
                        """,
                userId,
                UUID.randomUUID().toString(),
                account.stripeAccountId(),
                account.bankAccountId(),
                StripeFixtures.fakeId("btok")
        );
        return account;
    }

    /**
     * One column of the payout under the reference, as text; null when the column is.
     */
    public static String column(JdbcTemplate jdbc, String reference, String name) {
        return jdbc.queryForObject(
                "SELECT " + name + "::text FROM payouts WHERE transaction_reference = ?",
                String.class,
                reference
        );
    }

    public static String bankState(JdbcTemplate jdbc, String stripeAccountId) {
        return jdbc.queryForObject(
                "SELECT bank_state FROM payout_accounts WHERE stripe_account_id = ?",
                String.class,
                stripeAccountId
        );
    }

    /**
     * Moves the payout's next try and the end of any claim two hours back, past the longest unknown backoff and the
     * lease, so the next {@code run()} takes it as it would once that time had passed. Relative, so the shift does
     * not depend on how the session's time zone reads the {@code TIMESTAMP} columns.
     */
    public static void makeDue(JdbcTemplate jdbc, String reference) {
        jdbc.update(
                """
                        UPDATE payouts
                        SET next_attempt_at = next_attempt_at - interval '2 hours',
                            claimed_until = claimed_until - interval '2 hours'
                        WHERE transaction_reference = ?
                        """,
                reference
        );
    }

    /**
     * How many events of the type the outbox holds for the reference, sent or not.
     */
    public static int outboxRows(JdbcTemplate jdbc, String reference, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                reference,
                eventType
        );
    }
}
