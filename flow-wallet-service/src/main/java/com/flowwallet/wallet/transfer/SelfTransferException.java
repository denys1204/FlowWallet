package com.flowwallet.wallet.transfer;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The caller named itself as the recipient. Maps to HTTP 400.
 * <p>
 * Refused rather than carried out as a movement of nothing. Let through, it would lock one row twice and write
 * two legs that cancel out, a ledger entry that means nothing. The request alone shows it can never be valid,
 * so it is answered before a connection is taken, and the Idempotency-Key is neither judged nor spent. It is
 * 400 rather than 422 because no wallet's state enters into it.
 */
public class SelfTransferException extends ApiException {
    public SelfTransferException() {
        super(HttpStatus.BAD_REQUEST, "A transfer must go to another user");
    }
}
