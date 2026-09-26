package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The transfer lost a lock or a version check and was rolled back. Maps to HTTP 503.
 * <p>
 * The lock order rules this out by design. If it happens anyway, a 500 would hide the one fact the client needs:
 * nothing moved, so a retry with the same key is safe. The cause is kept for the platform handler's log.
 * See docs/adr/0011-wallet-row-locking.md.
 */
public class TransferBusyException extends ApiException {
    public TransferBusyException(Throwable cause) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The wallets are busy and nothing was moved. Retry with the same Idempotency-Key.", cause
        );
    }
}
