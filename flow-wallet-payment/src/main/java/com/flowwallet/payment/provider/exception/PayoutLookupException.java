package com.flowwallet.payment.provider.exception;

/**
 * Thrown when the provider cannot say what it holds for a payout (its transfer, its payout) or for the platform (its
 * balance). Nothing was created, so the caller backs off and asks again; it carries no HTTP status of its own.
 */
public class PayoutLookupException extends RuntimeException {
    public PayoutLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
