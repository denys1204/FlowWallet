package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The currency is not an ISO 4217 code. Maps to HTTP 400.
 * <p>
 * The detail does not quote the value: the handler logs every 4xx detail, and a value with line breaks would forge
 * log lines, one of any length would be copied into the log and the response. See
 * docs/adr/0016-error-model-and-status-codes.md.
 */
public class InvalidCurrencyException extends ApiException {
    public InvalidCurrencyException() {
        super(HttpStatus.BAD_REQUEST, "Not an ISO 4217 currency code");
    }
}
