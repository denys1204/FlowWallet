package com.flowwallet.payment.provider.stripe.client;

import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.Event;
import com.stripe.model.ExternalAccount;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import com.stripe.model.TransferReversal;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.ExternalAccountCollectionCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.PayoutListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.stripe.param.TransferReversalCollectionCreateParams;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper for Stripe static methods to allow for easy unit testing via mocking.
 */
@Component
@RequiredArgsConstructor
public class StripeClient {
    private final StripeProperties stripe;

    public PaymentIntent createPaymentIntent(
            PaymentIntentCreateParams params,
            String idempotencyKey
    ) throws StripeException {
        return PaymentIntent.create(params, requestOptions(idempotencyKey));
    }

    /**
     * Reads a PaymentIntent as Stripe holds it now. A read needs no idempotency key.
     */
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId, requestOptions(null));
    }

    public Account createAccount(AccountCreateParams params, String idempotencyKey) throws StripeException {
        return Account.create(params, requestOptions(idempotencyKey));
    }

    public Account retrieveAccount(String accountId) throws StripeException {
        return Account.retrieve(accountId, requestOptions(null));
    }

    /**
     * Attaches a bank account to a connected account. stripe-java's static API creates one only through the
     * account's own external account list, so the caller passes the account it retrieved.
     */
    public ExternalAccount createExternalAccount(
            Account account,
            ExternalAccountCollectionCreateParams params,
            String idempotencyKey
    ) throws StripeException {
        return account.getExternalAccounts().create(params, requestOptions(idempotencyKey));
    }

    /**
     * The platform's balance, available and pending, per currency.
     */
    public Balance retrieveBalance() throws StripeException {
        return Balance.retrieve(requestOptions(null));
    }

    public Transfer createTransfer(TransferCreateParams params, String idempotencyKey) throws StripeException {
        return Transfer.create(params, requestOptions(idempotencyKey));
    }

    /**
     * Every transfer the filter matches, read page by page.
     */
    public List<Transfer> listTransfers(TransferListParams params) throws StripeException {
        RequestOptions options = requestOptions(null);
        return collect(Transfer.list(params, options).autoPagingIterable(params.toMap(), options));
    }

    public Transfer retrieveTransfer(String transferId) throws StripeException {
        return Transfer.retrieve(transferId, requestOptions(null));
    }

    /**
     * Reverses part of a transfer the platform made. stripe-java's static API creates a reversal only through the
     * transfer's own reversal list, so the caller passes the transfer it retrieved.
     */
    public TransferReversal createReversal(
            Transfer transfer,
            TransferReversalCollectionCreateParams params,
            String idempotencyKey
    ) throws StripeException {
        return transfer.getReversals().create(params, requestOptions(idempotencyKey));
    }

    /**
     * Pays out of the connected account, not the platform.
     */
    public Payout createPayout(
            PayoutCreateParams params,
            String idempotencyKey,
            String stripeAccount
    ) throws StripeException {
        return Payout.create(params, requestOptions(idempotencyKey, stripeAccount));
    }

    /**
     * Every payout of the connected account that the filter matches, read page by page.
     */
    public List<Payout> listPayouts(PayoutListParams params, String stripeAccount) throws StripeException {
        RequestOptions options = requestOptions(null, stripeAccount);
        return collect(Payout.list(params, options).autoPagingIterable(params.toMap(), options));
    }

    public Payout retrievePayout(String payoutId, String stripeAccount) throws StripeException {
        return Payout.retrieve(payoutId, requestOptions(null, stripeAccount));
    }

    RequestOptions requestOptions(@Nullable String idempotencyKey) {
        return requestOptions(idempotencyKey, null);
    }

    /**
     * Sets the timeouts and retries on every call, so none of stripe-java's global defaults applies. A Connect call
     * on a connected account names it as {@code stripeAccount}; a call on the platform leaves it null.
     * See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
     */
    RequestOptions requestOptions(@Nullable String idempotencyKey, @Nullable String stripeAccount) {
        StripeProperties.Api api = stripe.getApi();
        return RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .setStripeAccount(stripeAccount)
                .setConnectTimeout(Math.toIntExact(api.getConnectTimeout().toMillis()))
                .setReadTimeout(Math.toIntExact(api.getReadTimeout().toMillis()))
                .setMaxNetworkRetries(api.getMaxNetworkRetries())
                .build();
    }

    public boolean isWebhookVerificationEnabled() {
        return stripe.getWebhook().hasSigningSecret();
    }

    /**
     * Checks the signature over the raw body without parsing it. A malformed header can surface as a
     * {@code RuntimeException} from the SDK's header parsing rather than as a {@link SignatureVerificationException}.
     */
    public void verifyWebhookSignature(String payload, String signature) throws SignatureVerificationException {
        Webhook.Signature.verifyHeader(
                payload,
                signature,
                stripe.getWebhook().getSecret(),
                stripe.getWebhook().getToleranceSeconds()
        );
    }

    /**
     * Parses the body into an event. {@code Webhook.constructEvent} deserializes the body before it checks the
     * signature, so it is called only once {@link #verifyWebhookSignature} has accepted the same body and header.
     */
    public Event constructVerifiedEvent(String payload, String signature) throws SignatureVerificationException {
        return Webhook.constructEvent(
                payload,
                signature,
                stripe.getWebhook().getSecret(),
                stripe.getWebhook().getToleranceSeconds()
        );
    }

    /**
     * Reads every page. A page after the first is fetched inside the iterator, which wraps its
     * {@link StripeException} in a {@code RuntimeException}; it is unwrapped so the caller sees one kind of failure.
     */
    static <T> List<T> collect(Iterable<T> pages) throws StripeException {
        List<T> all = new ArrayList<>();
        try {
            pages.forEach(all::add);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof StripeException stripeException) {
                throw stripeException;
            }
            throw e;
        }
        return all;
    }
}
