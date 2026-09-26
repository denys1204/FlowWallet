package com.flowwallet.wallet.transfer;

import com.flowwallet.wallet.api.AmountPrecision;
import com.flowwallet.wallet.api.Currencies;
import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.dto.TransferRequest;
import com.flowwallet.wallet.enums.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Moves money from the caller's wallet to another user's wallet in the same currency, and answers every retry
 * of one Idempotency-Key with the same transfer.
 * <p>
 * Deliberately not {@code @Transactional}, like {@code DepositService} and {@code PaymentEventListener}. The
 * money moves in {@link TransferHandler}'s transaction, and this class works on both sides of it. Before it,
 * everything the request alone can settle is settled with no connection taken: the currency, the amount's
 * precision, a transfer to oneself. After it, an integrity violation is explained by reading the ledger again.
 * A violation aborts the transaction in Postgres, so those reads can only run once the handler's transaction
 * has rolled back, from a caller outside it. If this class opened a transaction, the handler would join it and
 * the reads would run on the aborted one.
 * <p>
 * The rejected shape is a single transactional bean that catches the violation at the flush and answers 409,
 * as {@code PaymentTransactionStore.reserve} does. Apart from the aborted transaction, it would report every
 * violation as a used key. The ledger's CHECKs exist to catch a rule the code failed to keep, such as an
 * overdraft that got past {@code Wallet.debit}, and a 409 would hide that defect and send the client to a new
 * key. Here only a {@code TRANSFER_OUT} under the key explains a violation, and anything else is rethrown as
 * the bug it is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {
    private final TransferHandler handler;
    private final WalletRepository wallets;
    private final BalanceHistoryRepository movements;

    /**
     * @param userId         the caller, already lower-cased by {@code CurrentUserIdResolver}
     * @param currency       the path's currency, as the caller wrote it
     * @param idempotencyKey the caller's key, in either case
     * @return the receipt for the transfer this key names, whether it was made now or earlier
     */
    public TransferResponse transfer(String userId, String currency, String idempotencyKey, TransferRequest request) {
        String code = Currencies.normalise(currency);
        String reference = idempotencyKey.toLowerCase(Locale.ROOT);
        String recipient = request.to().toLowerCase(Locale.ROOT);
        BigDecimal amount = AmountPrecision.canonical(request.amount(), code);

        // Compared after both ids are lower-cased, or an upper-cased copy of the caller's own id would get
        // through as someone else.
        if (recipient.equals(userId)) {
            throw new SelfTransferException();
        }

        var command = new TransferCommand(userId, recipient, code, reference, amount);
        try {
            return handler.execute(command);
        } catch (DataIntegrityViolationException e) {
            return explain(command, e);
        } catch (ConcurrencyFailureException e) {
            // A deadlock victim, a lock wait that timed out, or a version conflict. Nothing committed.
            throw new TransferBusyException(e);
        }
    }

    /**
     * Explains an integrity violation from the ledger as it stands after the rollback.
     * <p>
     * Only a {@code TRANSFER_OUT} under the key explains one: another sender wallet wrote it first, and this
     * transfer lost at the unique index. That is a 409, unless the row turns out to be this caller's own
     * transfer on the same terms. Requests from one wallet serialize on its lock and see each other's legs
     * inside the handler, so that case should never reach the index. It is answered as a replay all the same,
     * because the alternative is a 409 that sends the client to a new key and moves the money a second time.
     * <p>
     * With no {@code TRANSFER_OUT} under the key, either a CHECK fired, which means a rule the code should
     * have kept was bypassed, or a value overflowed its column. The one overflow a transfer can reach is a
     * recipient's balance growing past what {@code NUMERIC(19,4)} holds, which no rule in the code guards.
     * Either way the violation is rethrown and becomes a 500 logged with its stack trace, never a conflict or
     * a success.
     * <p>
     * Both reads are repository queries with no transaction of their own, and they run after the handler's
     * transaction has rolled back. The pool may hand them the very connection that transaction used. That is
     * safe because the rollback ended the aborted transaction, not because the connection is a different one.
     */
    private TransferResponse explain(TransferCommand command, DataIntegrityViolationException violation) {
        Optional<BalanceHistory> taken =
                movements.findByTransactionReferenceAndType(command.reference(), TransactionType.TRANSFER_OUT);
        if (taken.isEmpty()) {
            throw violation;
        }

        boolean ownRepeat = wallets.findByUserIdAndCurrency(command.senderUserId(), command.currency())
                .map(wallet -> taken.get().isRepeatOf(wallet.getId(), command.recipientUserId(), command.amount()))
                .orElse(false);
        if (ownRepeat) {
            log.warn("Transfer {} reached the unique index although its sender's wallet lock should have "
                    + "serialized it; answering with the original", command.reference());
            return TransferResponse.of(taken.get(), command.currency());
        }

        log.info("Idempotency-Key {} went to another wallet's transfer, which reached the unique index first",
                command.reference());
        throw new ConflictingTransferException();
    }
}
