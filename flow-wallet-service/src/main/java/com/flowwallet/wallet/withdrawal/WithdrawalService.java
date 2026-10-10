package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.api.Currencies;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Withdraws from the caller's wallet to their bank account, and answers every retry of one Idempotency-Key with the
 * same withdrawal.
 * <p>
 * Deliberately not {@code @Transactional}, and the wallet's {@code open-in-view} stays off: it works on both sides of
 * {@link WithdrawalHandler}'s transaction and holds no connection while Payment Service answers the preflight. Before
 * the handler, everything the request and Payment Service settle is refused with nothing written. After it, an
 * integrity violation is explained by reading again, which works only once that transaction has rolled back. If this
 * class opened a transaction, the handler would join it, its lock would return the wallet this class read, and
 * Hibernate would fail the withdrawal whenever a credit committed in between.
 * See docs/adr/0006-short-transactions-across-bean-boundaries.md and
 * docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {
    private final WithdrawalHandler handler;
    private final WithdrawalKeys keys;
    private final WithdrawalPreflight preflight;
    private final WalletRepository wallets;
    private final WithdrawalRepository withdrawals;

    /**
     * A replay is answered before Payment Service is asked, so it works while Payment Service is down and whatever its
     * terms are now. A new key goes through the preflight, then the handler, which judges the key again under the lock.
     *
     * @param userId         the caller, already lower-cased by {@code CurrentUserIdResolver}
     * @param currency       the path's currency, as the caller wrote it
     * @param idempotencyKey the caller's key, in either case
     * @return the receipt for the withdrawal this key names, whether it was debited now or earlier
     * @throws WithdrawalRefusedException if this key's withdrawal was refused, now or earlier
     */
    public WithdrawalResponse withdraw(
            String userId,
            String currency,
            String idempotencyKey,
            WithdrawalRequest request
    ) {
        String code = Currencies.normalise(currency);
        String reference = idempotencyKey.toLowerCase(Locale.ROOT);
        BigDecimal amount = AmountPrecision.canonical(request.amount(), code);
        var terms = new WithdrawalTerms(userId, code, reference, amount);

        Long walletId = wallets.findByUserIdAndCurrency(userId, code)
                .orElseThrow(() -> new WalletNotFoundException(code))
                .getId();
        Optional<WithdrawalOutcome> earlier = keys.judge(reference, walletId, amount);
        if (earlier.isPresent()) {
            return answer(earlier.get(), code);
        }

        BankAccountState bankAccount = preflight.check(terms);
        WithdrawalOutcome outcome;
        try {
            outcome = handler.execute(terms, bankAccount);
        } catch (DataIntegrityViolationException e) {
            outcome = explain(terms, walletId, e);
        } catch (ConcurrencyFailureException e) {
            // A deadlock victim, a lock wait that timed out, or a version conflict. Nothing committed.
            throw new WithdrawalBusyException(e);
        }
        return answer(outcome, code);
    }

    /**
     * @param reference the withdrawal's key, in either case
     * @throws WithdrawalNotFoundException if no withdrawal of the caller's wallet in the currency has the reference
     */
    public WithdrawalStatusResponse read(String userId, String currency, String reference) {
        String code = Currencies.normalise(currency);
        Long walletId = wallets.findByUserIdAndCurrency(userId, code)
                .orElseThrow(() -> new WalletNotFoundException(code))
                .getId();
        return withdrawals.findByTransactionReference(reference.toLowerCase(Locale.ROOT))
                .filter(withdrawal -> withdrawal.getWalletId().equals(walletId))
                .map(withdrawal -> WithdrawalStatusResponse.of(withdrawal, code))
                .orElseThrow(() -> new WithdrawalNotFoundException(code));
    }

    /**
     * Explains an integrity violation from the rows as they stand after the rollback.
     * <p>
     * Only a withdrawal under the key explains one. On another wallet or amount it reached a unique index first, which
     * is a 409. On the caller's own terms it is answered as a replay although the wallet lock should have prevented
     * the race, because a 409 would send the client to a new key and move the money twice. No such withdrawal means a
     * CHECK fired or a value overflowed its column, and the violation is rethrown as a 500.
     * <p>
     * The reads have no transaction of their own and may get the handler's pooled connection back, which is safe
     * because the rollback ended the aborted transaction.
     */
    private WithdrawalOutcome explain(WithdrawalTerms terms, Long walletId, DataIntegrityViolationException violation) {
        Optional<WithdrawalOutcome> found;
        try {
            found = keys.judge(terms.reference(), walletId, terms.amount());
        } catch (ConflictingWithdrawalException conflict) {
            log.info(
                    "Idempotency-Key {} went to another wallet's withdrawal, which reached the unique index first",
                    terms.reference()
            );
            throw conflict;
        }
        if (found.isEmpty()) {
            throw violation;
        }
        log.warn(
                "Withdrawal {} reached the unique index although its wallet lock should have serialized it; "
                        + "answering with the original",
                terms.reference()
        );
        return found.get();
    }

    private static WithdrawalResponse answer(WithdrawalOutcome outcome, String currency) {
        return switch (outcome) {
            case WithdrawalOutcome.Debited debited -> WithdrawalResponse.of(debited.debit(), currency);
            case WithdrawalOutcome.Refused refused -> throw new WithdrawalRefusedException(refused.reason(), currency);
        };
    }
}
