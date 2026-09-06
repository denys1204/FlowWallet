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
 * Every lookup is scoped to the caller. That is not a check performed after loading — it is the only query
 * the service issues, so there is no wider read for a later change to forget to narrow, and "not yours" and
 * "does not exist" are the same result by construction.
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
     * Inserts and lets the unique constraint decide, rather than checking first: two concurrent first
     * requests would both pass a check and one would still fail on the insert, so the check would buy
     * nothing and hide the real arbiter.
     */
    @Transactional
    public WalletResponse open(String userId, String currency) {
        String code = Currencies.normalise(currency);
        try {
            return mapper.toResponse(wallets.saveAndFlush(Wallet.open(userId, code)));
        } catch (DataIntegrityViolationException e) {
            // Rethrown immediately and nothing else issued: in Postgres the violation has already aborted
            // this transaction, so any further statement would fail with a message about the abort rather
            // than about the conflict.
            log.info("User {} already holds a {} wallet", userId, code);
            throw new WalletAlreadyExistsException(code);
        }
    }

    @Transactional(readOnly = true)
    public WalletResponse read(String userId, String currency) {
        return mapper.toResponse(require(userId, Currencies.normalise(currency)));
    }

    /**
     * A page of movements, newest first. One extra row is fetched to learn whether an older page exists
     * without a second query or a count.
     */
    @Transactional(readOnly = true)
    public HistoryPage history(String userId, String currency, Long before, int limit) {
        Wallet wallet = require(userId, Currencies.normalise(currency));

        List<BalanceHistory> fetched = movements.findPageBefore(wallet.getId(), before, Limit.of(limit + 1));
        boolean hasOlder = fetched.size() > limit;
        List<BalanceHistory> page = hasOlder ? fetched.subList(0, limit) : fetched;

        return new HistoryPage(
                mapper.toHistoryResponses(page),
                hasOlder ? page.getLast().getId() : null
        );
    }

    private Wallet require(String userId, String currency) {
        return wallets.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new WalletNotFoundException(currency));
    }

}
