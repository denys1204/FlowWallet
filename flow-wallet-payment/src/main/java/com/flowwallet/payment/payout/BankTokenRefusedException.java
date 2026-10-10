package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when Stripe refuses the bank token: it does not exist, it was used already, or the bank account cannot take
 * payouts. A token works once, so the detail sends the caller to a new one and never quotes it.
 * See docs/adr/0026-problem-details-never-quote-rejected-input.md.
 */
public class BankTokenRefusedException extends ApiException {
    public BankTokenRefusedException() {
        super(HttpStatus.BAD_REQUEST, "Stripe refused the bank account token. Create a new token and retry.");
    }
}
