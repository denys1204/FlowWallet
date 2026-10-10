package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.enums.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The one judgement of a withdrawal's Idempotency-Key, which the service makes before the preflight, the handler again
 * under the wallet lock, and the service once more to explain an integrity violation, so the three answer alike.
 * <p>
 * Only withdrawals are consulted: a key a deposit or a transfer used is free for a withdrawal. It reads the
 * withdrawals and the ledger, never the wallets, so inside the handler it cannot load the wallet before its lock.
 * It has no transaction of its own: from the handler it joins the handler's, from the service each read runs on its
 * repository's own. See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@Component
@RequiredArgsConstructor
class WithdrawalKeys {
    private final WithdrawalRepository withdrawals;
    private final BalanceHistoryRepository movements;

    /**
     * @return what the key already came to, or empty when it is free
     * @throws ConflictingWithdrawalException if the key names a withdrawal of another wallet or amount
     */
    Optional<WithdrawalOutcome> judge(String reference, Long walletId, BigDecimal amount) {
        Optional<Withdrawal> found = withdrawals.findByTransactionReference(reference);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Withdrawal earlier = found.get();
        if (!earlier.hasTerms(walletId, amount)) {
            throw new ConflictingWithdrawalException();
        }
        if (earlier.getStatus() == WithdrawalStatus.REFUSED) {
            return Optional.of(new WithdrawalOutcome.Refused(earlier.getRefusalReason()));
        }
        // The withdrawal and its ledger row commit together and ledger rows are never deleted, so a missing one is a
        // defect, answered 500.
        BalanceHistory debit = movements.findByTransactionReferenceAndType(reference, TransactionType.WITHDRAWAL)
                .filter(row -> row.getWalletId().equals(walletId))
                .orElseThrow(() -> new IllegalStateException(
                        "Withdrawal " + reference + " has no WITHDRAWAL ledger row in its wallet"
                ));
        return Optional.of(new WithdrawalOutcome.Debited(debit));
    }
}
