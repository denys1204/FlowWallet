package com.flowwallet.payment.provider;

import java.util.Map;

import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.dto.WebhookResult;

public interface PaymentProviderStrategy {
    boolean supports(PaymentProvider provider);

    /**
     * Rejects what this provider can refuse without a network call. The caller runs it before the transaction row
     * is reserved, so a refusal leaves the reference free. See docs/adr/0013-deposit-initiation.md.
     */
    void validateRequest(PaymentRequestContext context);

    /**
     * Creates the payment at the provider, with the transaction reference as the provider's idempotency key, so
     * that a retry for a reserved row gets the same payment back. See docs/adr/0013-deposit-initiation.md.
     *
     * @return the provider's id for the payment and the data the client needs to finish it (for Stripe,
     *         {@code clientSecret})
     */
    PaymentInitiationResult initiatePayment(PaymentRequestContext context);

    /**
     * Verifies and parses a webhook. The caller applies the provider-agnostic result, so a strategy depends on no
     * transaction service and stays clear of a cycle through {@code PaymentService}, which depends on it.
     *
     * @param payload the raw request body, which the signature covers
     * @param headers the request headers, which carry the signature
     * @return the classified event, or {@link WebhookResult#unknown()} for one this service ignores
     */
    WebhookResult handleWebhook(String payload, Map<String, String> headers);

    /**
     * Asks the provider where a payment stands, for a payment whose webhook may never have arrived. The answer is
     * shaped like a webhook's, so the caller applies it through the same handler and the same checks.
     * See docs/adr/0032-pending-payments-are-rechecked-with-the-provider.md.
     *
     * @param providerTransactionId the provider's id for the payment, recorded when it was initiated
     * @return a success or a failure the provider has settled, or {@link WebhookResult#unknown()} while the
     *         customer can still pay
     * @throws com.flowwallet.payment.provider.exception.PaymentLookupException if the provider cannot be asked
     */
    WebhookResult checkPayment(String providerTransactionId);
}
