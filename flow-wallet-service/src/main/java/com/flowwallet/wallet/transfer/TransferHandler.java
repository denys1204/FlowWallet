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
 * The money transaction of a transfer: the debit, the credit and both ledger legs commit together or not at all.
 * <p>
 * Nothing is caught here: a constraint violation aborts the transaction in Postgres, so {@link TransferService}
 * classifies it after the rollback. No network call may run while the locks are held.
 * See docs/adr/0006-short-transactions-across-bean-boundaries.md.
 * <p>
 * The isolation stays pinned to READ COMMITTED. Under REPEATABLE READ a lock query that waited fails with a
 * serialization error, and the key lookup after the locks misses what a same-key request committed meanwhile.
 * See docs/adr/0011-wallet-row-locking.md.
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
     * The order of the steps decides both the answer and what a caller learns about others, and
     * {@code TransferHandlerTest} pins it. Both wallets are locked first, in ascending user id, as two calls and
     * before any decision, so transfers in opposite directions cannot deadlock. Nothing may load either wallet
     * before these calls: Hibernate would check the loaded instance's version against the locked row and fail the
     * transfer with a 503 whenever another writer committed in between.
     * <p>
     * The key is judged after the locks, which serialize same-key requests that share a wallet, and before the
     * debit, so a retry of a transfer that happened is never answered 422. Funds are judged before the recipient,
     * so only a request the caller can afford learns that a recipient wallet is absent. No statement runs between
     * the debit and that refusal, so it rolls back with nothing flushed.
     * <p>
     * The sending leg is saved before the receiving one and a single flush writes both, so same-key transfers
     * take the index entries in one order and every constraint fires inside the repository call, not at commit.
     * See docs/adr/0014-transfers-in-one-local-transaction.md and docs/adr/0011-wallet-row-locking.md.
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
