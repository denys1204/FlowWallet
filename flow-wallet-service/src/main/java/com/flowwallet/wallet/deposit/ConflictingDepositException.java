package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The idempotency key was already used — for a deposit on different terms, or for one that has already
 * completed. Maps to HTTP 409.
 * <p>
 * The two cases are not told apart, and that is a decision rather than an omission. Distinguishing them
 * would mean matching on another service's message text, which breaks the first time someone rewords it,
 * and it would buy the caller nothing: the remedy is a fresh key either way.
 */
public class ConflictingDepositException extends ApiException {
    public ConflictingDepositException() {
        super(HttpStatus.CONFLICT,
                "This Idempotency-Key was already used for a different deposit, or that deposit has "
                        + "already completed. Retry with a new key.");
    }
}
