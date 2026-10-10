package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service's preflight refused the withdrawal's terms: a currency it does not pay out in, an amount below the
 * minimum or off the payout grid. Maps to HTTP 400 with Payment Service's own words, which name the rule and never the
 * amount. Nothing was written, but a new attempt takes a new key: the terms come from configuration and could pass
 * later under the same one. See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public class WithdrawalRejectedException extends ApiException {
    public WithdrawalRejectedException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
