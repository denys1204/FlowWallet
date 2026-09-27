package com.flowwallet.wallet.api;

import com.flowwallet.wallet.balance.BalanceHistory;
import com.flowwallet.wallet.balance.BalanceHistoryRepository;
import com.flowwallet.wallet.balance.Wallet;
import com.flowwallet.wallet.balance.WalletRepository;
import com.flowwallet.wallet.dto.HistoryPage;
import com.flowwallet.wallet.dto.WalletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Everything a client can do to a wallet that does not move money.
 * <p>
 * Every query takes the caller's id, so ownership is part of the lookup and never a check after it.
 * See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository wallets;
    private final BalanceHistoryRepository movements;
    private final WalletMapper mapper;

    @Transactional(readOnly = true)
    public List<WalletResponse> listFor(String userId) {
        return mapper.toResponses(wallets.findByUserIdOrderByCurrency(userId));
    }

    /**
     * Opens a wallet, or refuses because the caller already holds one in this currency.
     * <p>
     * The unique {@code (user_id, currency)} constraint decides, with no check first. Every violation is reported
     * as that duplicate, which is true only while {@code normalise} and {@code Wallet.open} satisfy every other
     * constraint on the row. See docs/adr/0007-unique-constraints-decide.md.
     */
    @Transactional
    public WalletResponse open(String userId, String currency) {
        String code = Currencies.normaliseForNewWallet(currency);
        try {
            return mapper.toResponse(wallets.saveAndFlush(Wallet.open(userId, code)));
        } catch (DataIntegrityViolationException e) {
            // The violation has aborted this transaction, so nothing else is issued on it.
            log.info("User {} already holds a {} wallet", userId, code);
            throw new WalletAlreadyExistsException(code);
        }
    }

    @Transactional(readOnly = true)
    public WalletResponse read(String userId, String currency) {
        return mapper.toResponse(require(userId, Currencies.normalise(currency)));
    }

    /**
     * A page of movements, newest first by entry number. {@code before} is the entry number of the oldest
     * movement the client already holds. See docs/adr/0021-per-wallet-ledger-entry-numbers.md.
     */
    @Transactional(readOnly = true)
    public HistoryPage history(String userId, String currency, Long before, int limit) {
        Wallet wallet = require(userId, Currencies.normalise(currency));

        Limit oneMore = Limit.of(limit + 1);
        List<BalanceHistory> fetched = before == null
                ? movements.findNewest(wallet.getId(), oneMore)
                : movements.findPageBefore(wallet.getId(), before, oneMore);
        boolean hasOlder = fetched.size() > limit;
        List<BalanceHistory> page = hasOlder ? fetched.subList(0, limit) : fetched;

        return new HistoryPage(
                mapper.toHistoryResponses(page),
                hasOlder ? page.getLast().getEntryNo() : null
        );
    }

    private Wallet require(String userId, String currency) {
        return wallets.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new WalletNotFoundException(currency));
    }

}
