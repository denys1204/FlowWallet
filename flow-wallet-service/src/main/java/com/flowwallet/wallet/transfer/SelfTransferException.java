package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The caller named itself as the recipient. Maps to HTTP 400.
 * <p>
 * The request alone rules it out, so {@link TransferService} refuses it before a connection is taken, and the
 * Idempotency-Key is neither judged nor spent. See docs/adr/0014-transfers-in-one-local-transaction.md.
 */
public class SelfTransferException extends ApiException {
    public SelfTransferException() {
        super(HttpStatus.BAD_REQUEST, "A transfer must go to another user");
    }
}
