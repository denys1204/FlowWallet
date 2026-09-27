package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The idempotency key was already used, for a deposit on different terms or for one that has already
 * completed. Maps to HTTP 409.
 * <p>
 * The cases share one answer because the remedy is a new key either way, and telling them apart would mean
 * matching Payment Service's message text. See docs/adr/0005-client-supplied-idempotency-keys.md.
 */
public class ConflictingDepositException extends ApiException {
    public ConflictingDepositException() {
        super(
                HttpStatus.CONFLICT,
                "This Idempotency-Key was already used for a different deposit, or that deposit has "
                        + "already completed. Retry with a new key."
        );
    }
}
