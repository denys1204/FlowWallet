package com.flowwallet.payment.transaction;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A concurrent request with the same reference and terms reserved the row first and has not recorded the
 * provider's answer yet. Nothing was charged, and a retry with the same key gets that answer or finishes the
 * initiation itself. See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
 */
public class PaymentInProgressException extends ApiException {
    public PaymentInProgressException(String transactionReference) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ("A concurrent request with transaction reference %s is still starting this payment. "
                        + "Retry with the same Idempotency-Key.").formatted(transactionReference)
        );
    }
}
