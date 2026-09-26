package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The recipient holds no wallet in the transfer's currency. Maps to HTTP 422.
 * <p>
 * Not 404. On this path a 404 means the caller's own wallet is missing, and a client should be able to
 * conclude that from the status alone, since a problem response carries no type to branch on. The request is
 * well-formed and addresses a wallet that exists; what stops it is the state of another one.
 * <p>
 * The detail echoes no id, and it says the recipient holds no wallet rather than that no such user exists.
 * The wallet keeps no user registry, so it cannot tell a user without a wallet from an id that belongs to
 * nobody, and the wording must not suggest that it can.
 * <p>
 * What the refusal reveals is bounded by the order of the checks. Funds are judged first, so only a request
 * the caller can afford gets this answer, and it can only confirm that a wallet is absent. Learning that one
 * exists takes a completed transfer, which moves money and leaves the caller's id in the recipient's history.
 * {@link TransferHandler} logs each refusal with both user ids, so a caller probing for wallets shows up in
 * the logs.
 */
public class RecipientHasNoWalletException extends ApiException {
    public RecipientHasNoWalletException(String currency) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "The recipient holds no " + currency + " wallet");
    }
}
