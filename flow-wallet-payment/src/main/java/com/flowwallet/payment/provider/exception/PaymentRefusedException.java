package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the provider refuses to create the payment because of the request's own content, a refusal a retry
 * of the same request gets again. It comes after the transaction row was reserved, so the reference stays bound to
 * the refused terms and the detail sends the caller to a new key.
 * See docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md.
 */
public class PaymentRefusedException extends ApiException {
    public PaymentRefusedException(String providerMessage, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, detail(providerMessage), cause);
    }

    private static String detail(String providerMessage) {
        String reason = providerMessage == null || providerMessage.isBlank()
                ? "no reason given"
                : providerMessage.strip();
        return "The payment provider refused the payment: %s%s Correct the request and retry with a new "
                .formatted(reason, reason.endsWith(".") ? "" : ".")
                + "Idempotency-Key; this one stays bound to the refused request.";
    }
}
