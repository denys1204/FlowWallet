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
 * Deliberately not {@code @Transactional}: it works on both sides of {@link TransferHandler}'s transaction.
 * Before it, everything the request alone settles is refused with no connection taken. After it, an integrity
 * violation is explained by reading the ledger again, which works only once that transaction has rolled back. If
 * this class opened a transaction, the handler would join it and the reads would run on the aborted one.
 * See docs/adr/0006-short-transactions-across-bean-boundaries.md.
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
     * Only a {@code TRANSFER_OUT} under the key explains one: another sender wallet reached the unique index
     * first, which is a 409. If that row is the caller's own transfer on the same terms, it is answered as a
     * replay although the wallet lock should have prevented the race, because a 409 would send the client to a
     * new key and move the money twice. No such row means a CHECK fired or a value overflowed its column, and the
     * violation is rethrown as a 500.
     * <p>
     * The reads have no transaction of their own and may get the handler's pooled connection back, which is safe
     * because the rollback ended the aborted transaction. See docs/adr/0014-transfers-in-one-local-transaction.md.
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
