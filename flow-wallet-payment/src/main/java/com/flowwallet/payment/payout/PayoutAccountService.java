package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutAccountResponse;
import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.PayoutAccountHolder;
import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.ProviderBankAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Sets and reads a user's bank account for payouts. The first request reserves the user's row, opens a connected
 * account at Stripe under the row's key, and attaches the token; later ones attach a new token to the same account.
 * <p>
 * Not {@code @Transactional}: each Stripe call runs between {@link PayoutAccountStore}'s short transactions, and a
 * lost reservation is read back from a fresh one. A token equal to the one last applied is answered from the row,
 * because Stripe accepts a token once. Log lines name the account key, never the user id.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutAccountService {
    private final PayoutAccountStore store;
    private final PayoutProvider provider;
    private final PayoutProperties properties;

    public PayoutAccountResponse bankAccount(String userId) {
        return store.findByUser(userId)
                .map(PayoutAccountService::toResponse)
                .orElseGet(() -> new PayoutAccountResponse(BankAccountState.NOT_SET.name(), null, null));
    }

    public PayoutAccountResponse setBankAccount(String userId, String token) {
        PayoutAccount account = store.findByUser(userId).orElseGet(() -> reserve(userId));
        if (account.isLastTokenApplied(token)) {
            return toResponse(account);
        }

        String stripeAccountId = account.getStripeAccountId() != null
                ? account.getStripeAccountId()
                : openAccount(account).getStripeAccountId();

        return switch (provider.attachBankAccount(stripeAccountId, account.getAccountKey(), token)) {
            case PayoutCallResult.Done<ProviderBankAccount>(ProviderBankAccount bankAccount) -> {
                PayoutAccount recorded = store.recordBankAccount(account.getId(), token, inPayoutTerms(bankAccount));
                log.info("Payout account {} has a bank account, {}", account.getAccountKey(), recorded.getBankState());
                yield toResponse(recorded);
            }
            case PayoutCallResult.Refused<ProviderBankAccount>(_, String code) -> {
                log.info("Stripe refused the bank token for payout account {} ({})", account.getAccountKey(), code);
                throw new BankTokenRefusedException();
            }
            case PayoutCallResult.Unknown<ProviderBankAccount>(String cause) -> {
                log.warn("Bank account for payout account {} is not settled: {}", account.getAccountKey(), cause);
                throw new PayoutAccountUnavailableException();
            }
            case PayoutCallResult.Defect<ProviderBankAccount>(String cause) -> {
                log.error("Bank account for payout account {} hit a defect: {}", account.getAccountKey(), cause);
                throw new PayoutAccountUnavailableException();
            }
        };
    }

    /**
     * The terms are accepted now, by the request that creates the row; the time is stored and sent on every retry. It
     * is cut to whole seconds, the unit Stripe takes, so the value the column rounds to is the value first sent.
     */
    private PayoutAccount reserve(String userId) {
        try {
            return store.reserve(userId, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        } catch (DataIntegrityViolationException e) {
            // A concurrent first request won the reservation; its row is read from a fresh transaction.
            return store.findByUser(userId).orElseThrow(() -> e);
        }
    }

    /**
     * Nothing in the account's parameters comes from the user, so every failure here is Payment Service's own, and
     * the caller gets a 502 whatever Stripe answered.
     */
    private PayoutAccount openAccount(PayoutAccount account) {
        PayoutCallResult<String> result = provider.createAccount(
                account.getAccountKey(),
                account.getTosAcceptedAt(),
                holder()
        );
        if (result instanceof PayoutCallResult.Done<String>(String stripeAccountId)) {
            log.info("Opened the connected account of payout account {}", account.getAccountKey());
            return store.recordStripeAccount(account.getId(), stripeAccountId);
        }
        log.error(
                "Stripe did not open the connected account of payout account {}: {}",
                account.getAccountKey(),
                result
        );
        throw new PayoutAccountUnavailableException();
    }

    /**
     * A bank account in a currency Payment Service does not pay out in is recorded, but no withdrawal can use it.
     */
    private ProviderBankAccount inPayoutTerms(ProviderBankAccount bankAccount) {
        boolean payoutCurrency = properties.getCurrencies()
                .stream()
                .anyMatch(entry -> entry.getCode().equalsIgnoreCase(bankAccount.currency()));
        if (payoutCurrency) {
            return bankAccount;
        }
        return new ProviderBankAccount(
                bankAccount.externalAccountId(),
                bankAccount.last4(),
                bankAccount.currency(),
                false
        );
    }

    private PayoutAccountHolder holder() {
        PayoutProperties.Account identity = properties.getAccount();
        return new PayoutAccountHolder(
                identity.getCountry(),
                identity.getFirstName(),
                identity.getLastName(),
                identity.getEmail(),
                identity.getDateOfBirth(),
                identity.getAddressLine1(),
                identity.getCity(),
                identity.getPostalCode(),
                identity.getPhone(),
                identity.getProductDescription(),
                identity.getMerchantCategoryCode(),
                identity.getTosIp()
        );
    }

    private static PayoutAccountResponse toResponse(PayoutAccount account) {
        return new PayoutAccountResponse(
                account.getBankState().name(),
                account.getBankLast4(),
                account.getBankCurrency()
        );
    }
}
