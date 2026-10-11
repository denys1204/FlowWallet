package com.flowwallet.wallet.balance;

import com.flowwallet.wallet.support.WalletIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema's own rules, written to directly with SQL as any writer that skips the domain code might. Each must refuse
 * the write under its own name: the names are quoted in CLAUDE.md, the ADRs and Javadoc.
 */
class SchemaChecksIT extends WalletIntegrationTest {
    /**
     * Copies the wallet's deposit with the given type, amount, reference and entry number, keeping every other column.
     */
    private static final String COPY_OF_THE_DEPOSIT = """
            INSERT INTO balance_history (id, wallet_id, entry_no, transaction_reference, type, amount, balance_before,
                                         balance_after, created_at)
            SELECT nextval('balance_history_seq'), wallet_id, %s, %s, '%s', %s, balance_after, balance_after,
                   created_at
            FROM balance_history
            WHERE wallet_id = (SELECT id FROM wallets WHERE user_id = ?) AND type = 'DEPOSIT'
            """;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            wallets_balance_not_negative               | UPDATE wallets SET balance = -1 WHERE user_id = ?
            balance_history_amount_positive            | DEPOSIT, 0, new reference, next entry
            balance_history_counterparty_on_transfers  | TRANSFER_OUT, 1, new reference, next entry
            balance_history_wallet_entry_no_key        | DEPOSIT, 1, new reference, same entry
            balance_history_reference_type_key         | DEPOSIT, 1, same reference, next entry
            """)
    void theSchemaRefusesAWriteThatBreaksItsRuleUnderTheRulesOwnName(String constraint, String write) {
        // Guards the last line of defence being dropped or renamed by a migration: the domain code refuses these
        // writes first, so only a direct write shows that the database still does.
        String userId = newUserId();
        openWallet(userId, "PLN");
        fund(userId, "PLN", new BigDecimal("10.00"));

        assertThatThrownBy(() -> jdbc.update(sql(write), userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @ParameterizedTest(name = "a currency written as {0}")
    @CsvSource({"pln", "Pln"})
    void aWalletCurrencyIsStoredInUpperCaseOnly(String currency) {
        // Guards wallets_currency_is_upper: a second wallet in "pln" beside "PLN" would split one user's money in two.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wallets (id, user_id, balance, currency, version, last_entry_no, created_at, updated_at)
                VALUES (nextval('wallets_seq'), ?, 0, ?, 0, 0, now(), now())
                """, newUserId(), currency))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("wallets_currency_is_upper");
    }

    /**
     * Turns a row of the table into SQL: either the statement itself, or "type, amount, reference, entry" for a copy
     * of the wallet's deposit.
     */
    private static String sql(String write) {
        if (write.startsWith("UPDATE")) {
            return write;
        }
        String[] parts = write.split(",\\s*");
        String reference = parts[2].equals("same reference") ? "transaction_reference" : "gen_random_uuid()::text";
        String entry = parts[3].equals("same entry") ? "entry_no" : "entry_no + 1";
        return COPY_OF_THE_DEPOSIT.formatted(entry, reference, parts[0], parts[1]);
    }
}
