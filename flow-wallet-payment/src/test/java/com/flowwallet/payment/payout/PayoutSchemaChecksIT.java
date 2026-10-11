package com.flowwallet.payment.payout;

import com.flowwallet.payment.support.PaymentIntegrationTest;
import com.flowwallet.payment.support.PayoutRows;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payout tables' own rules, written to directly with SQL as any writer that skips {@link Payout} and
 * {@link PayoutAccount} might. Each must refuse the write under its own name, so a migration that drops or renames
 * a rule fails here.
 * <p>
 * The payout row each test seeds is {@code RETURNED}, a status neither the executor nor the reconciler reads, and
 * every write that would move it fails, so these tests leave {@code payouts} as other tests expect it and need not
 * empty it.
 */
class PayoutSchemaChecksIT extends PaymentIntegrationTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("payoutWrites")
    void thePayoutsTableRefusesAWriteThatBreaksItsRuleUnderTheRulesOwnName(String constraint, String set) {
        // Guards the last line of defence being dropped or renamed by a migration: Payout's guarded transitions
        // refuse these states first, so only a direct write shows that the database still does. A row in PAID without
        // its payout, or one waiting for an operator without the step to return to, would leave the executor, the
        // reconciler and the operator nothing to act on.
        String reference = newKey();
        jdbc.update(
                """
                        INSERT INTO payouts (id, transaction_reference, user_id, amount, currency, status, reason_code,
                            next_attempt_at)
                        VALUES (nextval('payouts_seq'), ?, ?, 10.0000, 'PLN', 'RETURNED', 'TERMS_REFUSED',
                            LOCALTIMESTAMP)
                        """,
                reference,
                newUserId()
        );

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payouts SET " + set + " WHERE transaction_reference = ?",
                reference
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    /**
     * Each rule of {@code payouts} with a write from a {@code RETURNED} row that breaks it and no other rule.
     */
    static Stream<Arguments> payoutWrites() {
        return Stream.of(
                Arguments.of("payouts_amount_positive", "amount = 0"),
                Arguments.of("payouts_status_known", "status = 'LOST'"),
                Arguments.of("payouts_reason_when_returning", "reason_code = NULL"),
                Arguments.of("payouts_transfer_when_transferred", "status = 'TRANSFERRED'"),
                Arguments.of(
                        "payouts_payout_when_created",
                        "status = 'PAID', stripe_transfer_id = 'tr_x', stripe_account_id = 'acct_x'"
                ),
                Arguments.of("payouts_attention_from_when_needed", "attention_from = 'ACCEPTED'"),
                Arguments.of(
                        "payouts_attention_cause_known",
                        "status = 'NEEDS_ATTENTION', attention_from = 'ACCEPTED', attention_cause = 'LOST'"
                ),
                Arguments.of("payouts_attention_cause_only_when_needed", "attention_cause = 'DEFECT'")
        );
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            payout_accounts_bank_state_known     | bank_state = 'LOST'
            payout_accounts_ready_has_accounts   | external_account_id = NULL
            payout_accounts_bank_currency_upper  | bank_currency = 'pln'
            """)
    void thePayoutAccountsTableRefusesAWriteThatBreaksItsRuleUnderTheRulesOwnName(String constraint, String set) {
        // Guards a payout account that claims READY without the connected account and bank account a payout needs,
        // or holds a state or currency spelling the code does not know, once a migration drops or renames the rule.
        String userId = newUserId();
        PayoutRows.seedReadyAccount(jdbc, userId);

        assertThatThrownBy(() -> jdbc.update("UPDATE payout_accounts SET " + set + " WHERE user_id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
