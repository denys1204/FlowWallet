package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The currency is not an ISO 4217 code. Maps to HTTP 400.
 */
public class InvalidCurrencyException extends ApiException {
    public InvalidCurrencyException(String currency) {
        super(HttpStatus.BAD_REQUEST, "Not an ISO 4217 currency code: " + currency);
    }
}
