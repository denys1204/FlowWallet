package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The transfer lost a lock or a version check and was rolled back. Maps to HTTP 503.
 * <p>
 * By design this does not happen. Every transfer locks both wallets in ascending user id before it writes, so
 * no two transfers can wait on each other in a cycle, and under the row lock {@code @Version} has nothing to
 * catch. A lock wait that times out is possible only once someone sets a {@code lock_timeout}. The exception
 * exists for the day that argument turns out wrong: a deadlock victim or a version conflict would otherwise
 * reach the platform handler as a 500, which hides the one fact the client needs. Nothing was moved, so a
 * retry with the same key is safe. The cause is kept, so the platform handler logs what actually failed.
 * <p>
 * 503 rather than the 502 of {@code PaymentUnavailableException}. That one reports a failure upstream of the
 * wallet; this one is contention inside it.
 */
public class TransferBusyException extends ApiException {
    public TransferBusyException(Throwable cause) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The wallets are busy and nothing was moved. Retry with the same Idempotency-Key.", cause
        );
    }
}
