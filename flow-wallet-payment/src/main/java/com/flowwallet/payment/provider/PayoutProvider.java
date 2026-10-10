package com.flowwallet.payment.provider;

import com.flowwallet.payment.provider.dto.ConnectEvent;
import com.flowwallet.payment.provider.dto.PayoutAccountHolder;
import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.ProviderBankAccount;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderTransfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Opens the user's connected account and attaches their bank account, then moves a withdrawal's money out in three
 * steps: a transfer from the platform to the connected account, a payout from that account to the user's bank, and,
 * when the payout fails, a reversal of the transfer back to the platform.
 * <p>
 * A create call reports a definite refusal only when the provider certainly created nothing. Any other failure leaves
 * the outcome unknown: the caller keeps the key generation and looks for the object before it creates again. A read
 * that fails throws {@link com.flowwallet.payment.provider.exception.PayoutLookupException}. Amounts are in major
 * units, and the payout's reference is the only identifier the provider receives.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public interface PayoutProvider {
    /**
     * Opens the connected account a user's payouts go through, with no bank account yet. The key and the terms'
     * acceptance time come from the reserved row, so a retry sends the same parameters.
     *
     * @return the provider's id for the account
     */
    PayoutCallResult<String> createAccount(String accountKey, Instant tosAcceptedAt, PayoutAccountHolder holder);

    /**
     * Attaches the bank account a single-use token stands for as the account's default for its currency. A refusal
     * means the token itself was refused.
     *
     * @return the account's default bank account for that currency after the attach
     */
    PayoutCallResult<ProviderBankAccount> attachBankAccount(String accountId, String accountKey, String token);

    /**
     * The connected account's default bank account for the currency as the provider holds it now, with whether payouts
     * can go to it; empty when the account has none for the currency.
     */
    Optional<ProviderBankAccount> findDefaultBankAccount(String accountId, String currency);

    /**
     * Verifies a webhook from the endpoint for connected accounts with that endpoint's own secret, and reduces it to
     * its envelope.
     *
     * @throws com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException for a missing or invalid
     *         signature, or while no secret is configured
     */
    ConnectEvent readConnectEvent(String payload, Map<String, String> headers);

    /**
     * The platform's balance in the currency that a transfer can use now; zero when the provider holds none.
     */
    BigDecimal availableBalance(String currency);

    /**
     * Transfers the amount to the connected account, grouped and labelled with the reference.
     *
     * @param keyGen the payout's key generation, part of the idempotency key
     */
    PayoutCallResult<ProviderTransfer> createTransfer(
            String reference,
            String accountId,
            BigDecimal amount,
            String currency,
            int keyGen
    );

    /**
     * The transfers made for the reference, found by their group: an earlier attempt's transfer that was never
     * recorded, or none.
     */
    List<ProviderTransfer> findTransfers(String reference);

    ProviderTransfer retrieveTransfer(String transferId);

    /**
     * Pays the amount out of the connected account to its default bank account for the currency.
     *
     * @param keyGen the payout's key generation, part of the idempotency key
     */
    PayoutCallResult<ProviderPayout> createPayout(
            String accountId,
            String reference,
            BigDecimal amount,
            String currency,
            int keyGen
    );

    /**
     * The connected account's payouts for the reference created since the given time, matched on their metadata.
     */
    List<ProviderPayout> findPayouts(String accountId, String reference, Instant createdSince);

    ProviderPayout retrievePayout(String accountId, String payoutId);

    /**
     * Brings the amount back from the connected account. When the transfer's reversed amount already covers it, an
     * earlier attempt succeeded, and its reversal is reported as done without a new one.
     *
     * @param keyGen the payout's key generation, part of the idempotency key
     * @return the reversal's id
     */
    PayoutCallResult<String> reverseTransfer(
            String transferId,
            String reference,
            BigDecimal amount,
            String currency,
            int keyGen
    );
}
