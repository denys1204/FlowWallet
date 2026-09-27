package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The code is valid ISO 4217 but names no currency of payment, such as gold or the testing code, so no deposit
 * could ever fund a wallet in it. Maps to HTTP 400.
 */
public class NonPaymentCurrencyException extends ApiException {
    public NonPaymentCurrencyException(String currency) {
        super(
                HttpStatus.BAD_REQUEST,
                "%s is not a currency of payment, so a wallet in it could never be funded".formatted(currency)
        );
    }
}
