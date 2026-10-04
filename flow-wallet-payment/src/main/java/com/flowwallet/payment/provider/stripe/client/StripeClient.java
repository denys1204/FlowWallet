package com.flowwallet.payment.provider.stripe.client;

import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

    /**
     * Sets the timeouts and retries on every call, so none of stripe-java's global defaults applies.
     * See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
     */
    RequestOptions requestOptions(String idempotencyKey) {
        StripeProperties.Api api = stripe.getApi();
        return RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
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
}
