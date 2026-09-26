package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service could not be reached, timed out, or answered in a way the wallet does not understand.
 * Maps to HTTP 502.
 * <p>
 * Nothing was charged, so a retry with the same key is safe. See docs/adr/0016-error-model-and-status-codes.md.
 */
public class PaymentUnavailableException extends ApiException {
    public PaymentUnavailableException(String detail) {
        super(HttpStatus.BAD_GATEWAY, detail);
    }
}
