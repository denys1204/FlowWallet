package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when no payout has the reference an operator named. The detail never quotes it.
 * See docs/adr/0026-problem-details-never-quote-rejected-input.md.
 */
public class PayoutNotFoundException extends ApiException {
    public PayoutNotFoundException() {
        super(HttpStatus.NOT_FOUND, "No payout has this reference.");
    }
}
