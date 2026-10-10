package com.flowwallet.wallet.bankaccount;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service refused the bank account request, for instance because Stripe refused the token. Maps to HTTP 400
 * with Payment Service's own wording, which never quotes the token.
 */
public class BankAccountRejectedException extends ApiException {
    public BankAccountRejectedException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
