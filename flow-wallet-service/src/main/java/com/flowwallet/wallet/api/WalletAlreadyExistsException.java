package com.flowwallet.wallet.api;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The caller already holds a wallet in this currency. Maps to HTTP 409.
 * <p>
 * A user may hold several wallets, but only one per currency — so a second one is a conflict rather than a
 * second wallet.
 */
public class WalletAlreadyExistsException extends ApiException {
    public WalletAlreadyExistsException(String currency) {
        super(HttpStatus.CONFLICT, "A " + currency + " wallet already exists");
    }
}
