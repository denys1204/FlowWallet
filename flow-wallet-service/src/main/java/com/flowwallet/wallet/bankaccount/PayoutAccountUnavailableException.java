package com.flowwallet.wallet.bankaccount;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service could not be reached, timed out, could not set the bank account at Stripe, or answered in a way
 * the wallet does not understand. Maps to HTTP 502. Payment Service keys each Stripe write and a read changes
 * nothing, so a retry with the same token is safe, and one whose first attempt went through is answered from its
 * row.
 * See docs/adr/0016-error-model-and-status-codes.md.
 */
public class PayoutAccountUnavailableException extends ApiException {
    public PayoutAccountUnavailableException(String detail) {
        super(HttpStatus.BAD_GATEWAY, detail);
    }
}
