package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The Idempotency-Key was already used for a different transfer. Maps to HTTP 409.
 * <p>
 * Every cause gets this one answer, including a race lost at the ledger's unique index. Naming the cause could
 * describe another user's transfer, and the remedy is a new key in every case.
 * See docs/adr/0005-client-supplied-idempotency-keys.md.
 */
public class ConflictingTransferException extends ApiException {
    public ConflictingTransferException() {
        super(
                HttpStatus.CONFLICT,
                "This Idempotency-Key was already used for a different transfer. Retry with a new key."
        );
    }
}
