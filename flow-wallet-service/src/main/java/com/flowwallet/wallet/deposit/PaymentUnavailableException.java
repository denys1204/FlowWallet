package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service could not be reached, timed out, or answered in a way the wallet does not understand.
 * Maps to HTTP 502.
 * <p>
 * 502 rather than 503: the wallet is working fine, and the failure is upstream of it. Nothing was charged —
 * the refusal happens before the caller is handed anything to pay with — so a retry with the same key is
 * safe, and the key is what makes it safe.
 */
public class PaymentUnavailableException extends ApiException {
    public PaymentUnavailableException(String detail) {
        super(HttpStatus.BAD_GATEWAY, detail);
    }
}
