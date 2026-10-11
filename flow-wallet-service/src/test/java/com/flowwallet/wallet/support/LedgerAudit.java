package com.flowwallet.wallet.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Checks that every wallet's ledger adds up: the balance and {@code last_entry_no} match the last entry, entries run
 * 1, 2, 3 with no gap, each one starts where the previous one ended, and each moves the balance by its amount in the
 * direction its type names. One statement, so it reads one snapshot even while other tests' transactions commit.
 */
public final class LedgerAudit {
    private static final String INCONSISTENT_WALLETS = """
            WITH entries AS (
                SELECT wallet_id, type, amount, balance_before, balance_after,
                       LAG(balance_after) OVER (PARTITION BY wallet_id ORDER BY entry_no) AS previous_after
                FROM balance_history
            ), totals AS (
                SELECT wallet_id, count(*) AS entries, max(entry_no) AS last_entry_no,
                       (array_agg(balance_after ORDER BY entry_no DESC))[1] AS last_balance
                FROM balance_history
                GROUP BY wallet_id
            )
            SELECT w.id
            FROM wallets w
            LEFT JOIN totals t ON t.wallet_id = w.id
            WHERE w.balance <> coalesce(t.last_balance, 0)
               OR w.last_entry_no <> coalesce(t.entries, 0)
               OR w.last_entry_no <> coalesce(t.last_entry_no, 0)
            UNION
            SELECT wallet_id
            FROM entries
            WHERE balance_before <> coalesce(previous_after, 0)
               OR balance_after <> CASE
                   WHEN type IN ('TRANSFER_OUT', 'WITHDRAWAL') THEN balance_before - amount
                   ELSE balance_before + amount
               END
            """;

    private LedgerAudit() {
    }

    public static List<Long> inconsistentWallets(JdbcTemplate jdbc) {
        return jdbc.queryForList(INCONSISTENT_WALLETS, Long.class);
    }
}
