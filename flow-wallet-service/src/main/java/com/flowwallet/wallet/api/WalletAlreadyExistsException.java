package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The caller already holds a wallet in this currency. Maps to HTTP 409.
 */
public class WalletAlreadyExistsException extends ApiException {
    public WalletAlreadyExistsException(String currency) {
        super(HttpStatus.CONFLICT, "A " + currency + " wallet already exists");
    }
}
