package com.flowwallet.wallet.withdrawal;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.contract.event.WithdrawalRequestedEvent;
import com.flowwallet.outbox.Outbox;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.InsufficientFundsException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The money transaction of a withdrawal: the debit, its ledger row, the {@code withdrawals} row and the command to
 * Payment Service commit together or not at all, or the refusal row commits alone.
 * <p>
 * No network call runs while the lock is held: the preflight ran before, and the command leaves through the outbox
 * after the commit. Nothing but the refusals is caught here: a constraint violation aborts the transaction in
 * Postgres, so {@link WithdrawalService} classifies it after the rollback.
 * See docs/adr/0006-short-transactions-across-bean-boundaries.md.
 * <p>
 * The isolation stays pinned to READ COMMITTED. Under REPEATABLE READ a lock query that waited fails with a
 * serialization error, and the key judged after the lock misses what a same-key request committed meanwhile.
 * See docs/adr/0011-wallet-row-locking.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalHandler {
    static final String AGGREGATE_TYPE_WITHDRAWAL = "Withdrawal";

    private final WalletRepository wallets;
    private final BalanceHistoryRepository movements;
    private final WithdrawalRepository withdrawals;
    private final WithdrawalKeys keys;
    private final Outbox outbox;

    /**
     * Debits the wallet, refuses the withdrawal on its record, or answers with what the key already came to.
     * <p>
     * The order of the steps decides the answer, and {@code WithdrawalHandlerTest} pins it. The wallet is locked first:
     * nothing may load it before, or Hibernate would check the loaded instance's version against the locked row and
     * fail the withdrawal with a 503 whenever a credit committed in between. The key is judged after the lock, which
     * serializes same-key requests on one wallet, and before the bank account's verdict, so a retry of a withdrawal
     * that happened is never refused. The bank account is judged before the funds.
     * <p>
     * A refusal is returned, not thrown, so its row commits and the key stays spent. {@code InsufficientFundsException}
     * is caught only around {@code Wallet.debit}, an entity call that crosses no transactional proxy, so the
     * transaction is not marked rollback-only; the debit refuses before it changes anything.
     * <p>
     * The ledger row, the withdrawal and the outbox row are saved in this order and written by one flush, so same-key
     * withdrawals on different wallets take the unique index entries in one order and every constraint fires inside
     * the flush. The command carries the locked wallet's user id and currency, which the payout events come back with.
     * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
     *
     * @throws WalletNotFoundException        if the caller holds no wallet in the currency
     * @throws ConflictingWithdrawalException if the key already names a different withdrawal
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WithdrawalOutcome execute(WithdrawalTerms terms, BankAccountState bankAccount) {
        Wallet wallet = wallets.lockByUserIdAndCurrency(terms.userId(), terms.currency())
                .orElseThrow(() -> new WalletNotFoundException(terms.currency()));

        Optional<WithdrawalOutcome> earlier = keys.judge(terms.reference(), wallet.getId(), terms.amount());
        if (earlier.isPresent()) {
            log.info("Withdrawal {} was already decided; answering with the original", terms.reference());
            return earlier.get();
        }

        if (bankAccount == BankAccountState.NOT_SET) {
            return refuse(wallet, terms, WithdrawalRefusalReason.NO_BANK_ACCOUNT);
        }
        BigDecimal balanceBefore;
        try {
            balanceBefore = wallet.debit(terms.amount());
        } catch (InsufficientFundsException e) {
            return refuse(wallet, terms, WithdrawalRefusalReason.INSUFFICIENT_FUNDS);
        }

        BalanceHistory debit = BalanceHistory.withdrawal(wallet, terms.reference(), terms.amount(), balanceBefore);
        movements.save(debit);
        withdrawals.save(Withdrawal.pending(wallet, terms.reference(), terms.amount()));
        outbox.enqueue(
                AGGREGATE_TYPE_WITHDRAWAL,
                terms.reference(),
                KafkaConstants.EVENT_TYPE_WITHDRAWAL_REQUESTED,
                new WithdrawalRequestedEvent(
                        UUID.randomUUID().toString(),
                        KafkaConstants.WITHDRAWAL_EVENT_SCHEMA_VERSION,
                        terms.reference(),
                        wallet.getUserId(),
                        terms.amount(),
                        wallet.getCurrency()
                )
        );
        withdrawals.flush();
        log.info(
                "Debited {} {} from wallet {} for withdrawal {}",
                terms.amount().toPlainString(),
                wallet.getCurrency(),
                wallet.getId(),
                terms.reference()
        );
        return new WithdrawalOutcome.Debited(debit);
    }

    private WithdrawalOutcome refuse(Wallet wallet, WithdrawalTerms terms, WithdrawalRefusalReason reason) {
        withdrawals.saveAndFlush(Withdrawal.refused(wallet, terms.reference(), terms.amount(), reason));
        log.info("Refused withdrawal {} from wallet {}: {}", terms.reference(), wallet.getId(), reason);
        return new WithdrawalOutcome.Refused(reason);
    }
}
