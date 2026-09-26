package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The caller holds no wallet in the requested currency. Maps to HTTP 404.
 * <p>
 * Another user's wallet gets the same answer, never a 403, because every lookup is scoped to the caller.
 * See docs/adr/0004-wallet-addressed-by-owner-and-currency.md.
 */
public class WalletNotFoundException extends ApiException {
    public WalletNotFoundException(String currency) {
        super(HttpStatus.NOT_FOUND, "No " + currency + " wallet");
    }
}
