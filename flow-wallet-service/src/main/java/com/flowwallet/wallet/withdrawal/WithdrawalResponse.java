package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.enums.TransactionType;

/**
 * The caller's receipt for a withdrawal the wallet debited.
 * <p>
 * A replay must return a byte-identical body, so nothing here varies with time or with the payout's progress: no
 * status, no timestamp. Only {@link #of} builds it, from the {@code WITHDRAWAL} ledger row, in memory for the first
 * answer and stored for a replay, and renders both amounts through {@code AmountPrecision.render}, so the two come out
 * alike. The status is read at the withdrawal's own address.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md and
 * docs/adr/0030-amounts-in-responses-are-decimal-strings.md.
 *
 * @param reference    the Idempotency-Key the withdrawal was made under, lower-cased
 * @param amount       how much was debited, as a decimal string at the currency's scale
 * @param currency     the wallet's currency
 * @param balanceAfter the balance right after this debit, which on a replay is a past balance
 */
public record WithdrawalResponse(String reference, String amount, String currency, String balanceAfter) {
    /**
     * @param debit    the withdrawal's ledger row; any other type is refused
     * @param currency the wallet's currency, which a movement does not carry
     */
    public static WithdrawalResponse of(BalanceHistory debit, String currency) {
        if (debit.getType() != TransactionType.WITHDRAWAL) {
            throw new IllegalArgumentException("A withdrawal receipt is built from its debit, got " + debit.getType());
        }
        return new WithdrawalResponse(
                debit.getTransactionReference(),
                AmountPrecision.render(debit.getAmount(), currency),
                currency,
                AmountPrecision.render(debit.getBalanceAfter(), currency)
        );
    }
}
