package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The Idempotency-Key was already used for a different withdrawal: another amount, another of the caller's wallets,
 * or another user's withdrawal. Maps to HTTP 409.
 * <p>
 * Every cause gets this one answer, including a race lost at a unique index. Naming the cause could describe another
 * user's withdrawal, and the remedy is a new key in every case.
 * See docs/adr/0005-client-supplied-idempotency-keys.md.
 */
public class ConflictingWithdrawalException extends ApiException {
    public ConflictingWithdrawalException() {
        super(
                HttpStatus.CONFLICT,
                "This Idempotency-Key was already used for a different withdrawal. Retry with a new key."
        );
    }
}
