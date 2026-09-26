package com.flowwallet.wallet.balance;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The wallet's balance does not cover a debit: 422, whose remedy is a smaller amount or a top-up, never a new key.
 * <p>
 * The message names the currency and no figures, because the platform handler logs every 4xx detail. It is an
 * {@code ApiException} because every caller of {@link Wallet#debit} serves an HTTP request; a debit driven by an
 * event would need a refusal of its own. See docs/adr/0016-error-model-and-status-codes.md.
 */
public class InsufficientFundsException extends ApiException {
    public InsufficientFundsException(String currency) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds in the " + currency + " wallet");
    }
}
