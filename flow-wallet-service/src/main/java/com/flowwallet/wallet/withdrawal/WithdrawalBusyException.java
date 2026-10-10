package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The withdrawal lost a lock or a version check and was rolled back. Maps to HTTP 503.
 * <p>
 * One wallet lock and fixed insert orders rule this out by design. If it happens anyway, a 500 would hide the one fact
 * the client needs: nothing was debited, so a retry with the same key is safe. The cause is kept for the platform
 * handler's log. See docs/adr/0011-wallet-row-locking.md.
 */
public class WithdrawalBusyException extends ApiException {
    public WithdrawalBusyException(Throwable cause) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The wallet is busy and nothing was debited. Retry with the same Idempotency-Key.",
                cause
        );
    }
}
