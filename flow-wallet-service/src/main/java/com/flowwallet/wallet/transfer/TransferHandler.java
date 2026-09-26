package com.flowwallet.wallet.transfer;

import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.InsufficientFundsException;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.enums.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The money transaction of a transfer: the debit, the credit and both ledger legs commit together or not at
 * all. It is one local transaction in wallet_db and no other service takes part, so there is no intermediate
 * state to compensate for.
 * <p>
 * Nothing is caught here, for the reason {@code PaymentEventHandler} gives: a constraint violation aborts the
 * transaction in Postgres, so {@link TransferService} classifies it after the rollback, from outside. No
 * network call is made while the locks are held, so every wait on them ends when a short, database-only
 * transaction does.
 * <p>
 * The isolation is pinned to READ COMMITTED instead of being left to the database default, because two steps
 * depend on it. A lock query that had to wait returns the holder's committed row only under READ COMMITTED;
 * under REPEATABLE READ it fails with a serialization error instead. And the key lookup after the locks must
 * see the legs a same-key request committed while this one waited, which takes a snapshot per statement. A
 * change to the database's {@code default_transaction_isolation} would otherwise break both without a sound.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferHandler {
    private final WalletRepository wallets;
    private final BalanceHistoryRepository movements;

    /**
     * Moves the money, or answers with the transfer this key already made.
     * <p>
     * Both wallets are locked first, in ascending user id, as two calls. Transfers between the same two users
     * then take their locks in the same order whichever way the money goes, and the second waits while holding
     * nothing. Locking the sender first would let A to B and B to A each hold one lock and wait for the other
     * until Postgres aborted one as a deadlock. The order is known from the request. Wallet id would give an
     * order too, but only after a read, and a read of either wallet earlier in this transaction would leave a
     * managed instance for the locking query to return. Hibernate then checks that instance's version against
     * the locked row and fails the transfer with a 503 whenever another writer committed in between, so a wait
     * that should end in success ends in an error. Nothing may load either wallet before these calls. A single
     * query over both ids, ordered for locking, would also work in Postgres, but it would put the order in the
     * query plan, where no test sees it. Both locks come before any decision, so which refusal a request gets
     * never depends on which id sorts first.
     * <p>
     * The key is judged after the locks and before the debit. Every transfer out of a wallet holds that
     * wallet's lock, so two requests with one key from one wallet serialize, and the second's lookup sees what
     * the first committed. Judged before the lock, a retry could pass while the original was in flight, reach
     * the debit after the original had spent the money, and answer 422 for a transfer that happened; judged
     * after the debit, it would do the same once the balance was spent. Requests from different sender wallets
     * serialize the same way when their transfers share any other wallet, such as the recipient, and the lookup
     * settles them too. Only between transfers that share no wallet does the ledger's unique index decide.
     * <p>
     * Funds are judged before the recipient. Opening a wallet costs nothing, so the caller's own 404 is no
     * barrier, and checking the recipient first would let anyone ask, for free, whether a user holds a wallet
     * in this currency. After the funds check, only a request the caller can afford learns that a wallet is
     * absent. The debit is made in memory before the recipient is known to exist; no statement runs between
     * the two, so a refusal there rolls back with nothing flushed.
     * <p>
     * The sending leg is persisted before the receiving one and a single flush writes both, so every transfer
     * takes the index entries for a key in the same order, and two racing on one key from different wallets
     * cannot wait on each other in a cycle. Every constraint fires inside that flush, as an exception from the
     * repository call rather than at commit.
     *
     * @return the receipt, built from the sending leg that this call wrote or that an earlier one did
     * @throws WalletNotFoundException       if the caller holds no wallet in the currency
     * @throws ConflictingTransferException  if the key already names a different transfer
     * @throws InsufficientFundsException    if the caller's balance is below the amount
     * @throws RecipientHasNoWalletException if the recipient holds no wallet in the currency
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse execute(TransferCommand command) {
        String sender = command.senderUserId();
        String recipient = command.recipientUserId();
        String currency = command.currency();
        String reference = command.reference();
        BigDecimal amount = command.amount();

        boolean senderSortsFirst = sender.compareTo(recipient) < 0;
        Optional<Wallet> lower = wallets.lockByUserIdAndCurrency(senderSortsFirst ? sender : recipient, currency);
        Optional<Wallet> higher = wallets.lockByUserIdAndCurrency(senderSortsFirst ? recipient : sender, currency);

        Wallet from = (senderSortsFirst ? lower : higher)
                .orElseThrow(() -> new WalletNotFoundException(currency));

        Optional<BalanceHistory> earlier =
                movements.findByTransactionReferenceAndType(reference, TransactionType.TRANSFER_OUT);
        if (earlier.isPresent()) {
            if (earlier.get().isRepeatOf(from.getId(), recipient, amount)) {
                log.info("Transfer {} was already made; answering with the original", reference);
                return TransferResponse.of(earlier.get(), currency);
            }
            throw new ConflictingTransferException();
        }

        BigDecimal senderBefore = from.debit(amount);

        Wallet to = (senderSortsFirst ? higher : lower).orElseThrow(() -> {
            log.warn("User {} tried to send {} {} to user {}, who holds no {} wallet (transfer {})",
                    sender, amount, currency, recipient, currency, reference);
            return new RecipientHasNoWalletException(currency);
        });
        BigDecimal recipientBefore = to.credit(amount);

        BalanceHistory out = BalanceHistory.transferOut(from, reference, to.getUserId(), amount, senderBefore);
        movements.save(out);
        movements.saveAndFlush(BalanceHistory.transferIn(to, reference, from.getUserId(), amount, recipientBefore));

        log.info("Transferred {} {} from wallet {} (user {}) to wallet {} (user {}) for transfer {}",
                amount, currency, from.getId(), from.getUserId(), to.getId(), to.getUserId(), reference);
        return TransferResponse.of(out, currency);
    }
}
