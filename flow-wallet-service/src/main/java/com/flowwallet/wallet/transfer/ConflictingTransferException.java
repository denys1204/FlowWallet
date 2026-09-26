package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The Idempotency-Key was already used for a different transfer. Maps to HTTP 409.
 * <p>
 * "Different" covers every cause alike: other terms from the same wallet, a transfer from another of the
 * caller's wallets, another user's transfer, a key copied from a transfer the caller received, and a race
 * lost at the ledger's unique index. The causes are not told apart, as {@code ConflictingDepositException}
 * does not tell its own apart. Naming the cause would describe a transfer that may belong to someone else, and
 * it would buy the caller nothing, because the remedy is a new key in every case.
 */
public class ConflictingTransferException extends ApiException {
    public ConflictingTransferException() {
        super(
                HttpStatus.CONFLICT,
                "This Idempotency-Key was already used for a different transfer. Retry with a new key."
        );
    }
}
