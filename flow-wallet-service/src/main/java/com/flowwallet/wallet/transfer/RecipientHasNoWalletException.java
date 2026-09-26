package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The recipient holds no wallet in the transfer's currency. Maps to HTTP 422, not 404, which on this path means
 * the caller's own wallet is missing.
 * <p>
 * The detail names no id and does not say the user is unknown, because the wallet keeps no user registry. What
 * the refusal reveals rests on {@link TransferHandler} judging funds first and logging each refusal.
 * See docs/adr/0014-transfers-in-one-local-transaction.md.
 */
public class RecipientHasNoWalletException extends ApiException {
    public RecipientHasNoWalletException(String currency) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "The recipient holds no " + currency + " wallet");
    }
}
