package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.balance.BalanceHistory;

/**
 * What a withdrawal came to under its key. A refusal is returned, not thrown, so the row that records it commits in
 * the same transaction and the key stays spent.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
sealed interface WithdrawalOutcome {
    /**
     * The wallet was debited; the receipt is built from this ledger row.
     */
    record Debited(BalanceHistory debit) implements WithdrawalOutcome {
    }

    record Refused(WithdrawalRefusalReason reason) implements WithdrawalOutcome {
    }
}
