package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The bank account is ready, but the platform's reserve cannot cover the payout now. Maps to HTTP 503; nothing was
 * debited, so a retry with the same key is safe. The detail says nothing about the reserve, which is the platform's.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public class WithdrawalsUnavailableException extends ApiException {
    public WithdrawalsUnavailableException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Withdrawals are temporarily unavailable. Nothing was debited; retry later with the same "
                        + "Idempotency-Key."
        );
    }
}
