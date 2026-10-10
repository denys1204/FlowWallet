package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.dto.ProviderBankAccount;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The short transactions around the Stripe calls of {@link PayoutAccountService}, one per step, so no connection is
 * held while Stripe answers. See docs/adr/0006-short-transactions-across-bean-boundaries.md.
 */
@Component
@RequiredArgsConstructor
public class PayoutAccountStore {
    private final PayoutAccountRepository accounts;

    @Transactional(readOnly = true)
    public Optional<PayoutAccount> findByUser(String userId) {
        return accounts.findByUserId(userId);
    }

    /**
     * Reserves the user's row with a fresh account key. The unique user id decides a race, so the caller reads the
     * winner after a violation. See docs/adr/0007-unique-constraints-decide.md.
     */
    @Transactional
    public PayoutAccount reserve(String userId, Instant tosAcceptedAt) {
        return accounts.saveAndFlush(PayoutAccount.reserve(userId, UUID.randomUUID().toString(), tosAcceptedAt));
    }

    @Transactional(readOnly = true)
    public Optional<PayoutAccount> findByStripeAccount(String stripeAccountId) {
        return accounts.findByStripeAccountId(stripeAccountId);
    }

    /**
     * Sets the state read from Stripe, unless the row changed since it was read or Stripe's default is another bank
     * account than the stored one: a request or a payout outcome that wrote meanwhile may hold the newer answer.
     *
     * @param seenVersion              the row's version when the refresh read it, before asking Stripe
     * @param defaultExternalAccountId Stripe's default bank account for the currency; null when there is none
     */
    @Transactional
    public BankRefresh applyBankState(
            Long id,
            Long seenVersion,
            @Nullable String defaultExternalAccountId,
            BankAccountState state
    ) {
        PayoutAccount account = lock(id);
        if (!Objects.equals(account.getVersion(), seenVersion)) {
            return BankRefresh.RACED;
        }
        if (defaultExternalAccountId != null && !defaultExternalAccountId.equals(account.getExternalAccountId())) {
            return BankRefresh.REPLACED;
        }
        return account.updateBankState(state) ? BankRefresh.CHANGED : BankRefresh.UNCHANGED;
    }

    @Transactional
    public PayoutAccount recordStripeAccount(Long id, String stripeAccountId) {
        PayoutAccount account = lock(id);
        account.recordStripeAccount(stripeAccountId);
        return account;
    }

    @Transactional
    public PayoutAccount recordBankAccount(Long id, String token, ProviderBankAccount bankAccount) {
        PayoutAccount account = lock(id);
        account.recordBankAccount(
                token,
                bankAccount.externalAccountId(),
                bankAccount.last4(),
                bankAccount.currency(),
                bankAccount.ready() ? BankAccountState.READY : BankAccountState.NOT_SET
        );
        return account;
    }

    private PayoutAccount lock(Long id) {
        return accounts.lockById(id).orElseThrow(() -> new IllegalStateException("Payout account " + id + " is gone"));
    }
}
