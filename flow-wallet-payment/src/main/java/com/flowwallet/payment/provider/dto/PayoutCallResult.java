package com.flowwallet.payment.provider.dto;

/**
 * What a {@link com.flowwallet.payment.provider.PayoutProvider} create call did. Only {@link Refused} says that
 * nothing was created; {@link Unknown} may stand for an object the provider holds, so the caller looks for it before
 * it creates again under the same key. See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public sealed interface PayoutCallResult<T> {
    /**
     * The provider created the object, or holds it from an earlier attempt.
     */
    record Done<T>(T value) implements PayoutCallResult<T> {
    }

    /**
     * A definite refusal: the provider created nothing and stores the refusal under the key.
     *
     * @param providerCode the provider's own code for the refusal, for the log line
     */
    record Refused<T>(PayoutRefusal refusal, String providerCode) implements PayoutCallResult<T> {
    }

    /**
     * No answer that settles the outcome: a timeout, a connection error, a conflict, a rate limit, a server error or
     * a refusal the provider's code does not make definite.
     *
     * @param cause what the provider reported, for the log line
     */
    record Unknown<T>(String cause) implements PayoutCallResult<T> {
    }

    /**
     * The key was sent with other parameters than its first use, which only a defect in this service causes.
     */
    record Defect<T>(String cause) implements PayoutCallResult<T> {
    }
}
