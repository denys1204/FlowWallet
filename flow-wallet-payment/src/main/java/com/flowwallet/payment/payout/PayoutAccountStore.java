package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.dto.ProviderBankAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
