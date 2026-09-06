package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The caller holds no wallet in the requested currency. Maps to HTTP 404.
 * <p>
 * 404 rather than 403, and the distinction is not cosmetic: every lookup is scoped to the caller, so "not
 * yours" and "does not exist" are literally the same query result. Answering 403 would mean deliberately
 * running a wider query first, which would turn the endpoint into an oracle for other people's wallets.
 */
public class WalletNotFoundException extends ApiException {
    public WalletNotFoundException(String currency) {
        super(HttpStatus.NOT_FOUND, "No " + currency + " wallet");
    }
}
