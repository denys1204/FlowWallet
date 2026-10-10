package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a withdrawal misses the terms Payment Service sets: a currency it does not pay out in, an amount below
 * the minimum, too large or off the payout grid. The detail names the rule and never the amount.
 * See docs/adr/0026-problem-details-never-quote-rejected-input.md.
 */
public class PayoutTermsRefusedException extends ApiException {
    public PayoutTermsRefusedException(String reason) {
        super(HttpStatus.BAD_REQUEST, reason + ".");
    }
}
