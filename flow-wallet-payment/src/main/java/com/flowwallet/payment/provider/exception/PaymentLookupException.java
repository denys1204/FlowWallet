package com.flowwallet.payment.provider.exception;

/**
 * Thrown when the provider cannot say where a payment stands. The reconciler logs it and asks again on a later run;
 * no request is waiting for the answer, so it carries no HTTP status.
 */
public class PaymentLookupException extends RuntimeException {
    public PaymentLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
