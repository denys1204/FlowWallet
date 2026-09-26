package com.flowwallet.wallet.balance;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The wallet's balance does not cover a debit. Maps to HTTP 422.
 * <p>
 * 422 rather than 409. On the transfer path a 409 means only "that Idempotency-Key was already used for a
 * different transfer", and its remedy is a new key. Here the key is unspent and the request well-formed, and the
 * remedy is a smaller amount or a top-up. A problem response carries no type, so the status is the only thing a
 * client can branch on, and the two answers must not share one.
 * <p>
 * An {@code ApiException}, unlike {@link UnknownWalletException}, because every debit in scope starts from an
 * HTTP request that is waiting for the answer. A debit driven by an event, such as a chargeback, would need a
 * refusal of its own.
 * <p>
 * The message names the currency and no figures. The platform handler writes every 4xx detail to the log, and
 * none of this service's own log lines carries a balance; the caller can read its own balance from the wallet
 * endpoint.
 */
public class InsufficientFundsException extends ApiException {
    public InsufficientFundsException(String currency) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds in the " + currency + " wallet");
    }
}
