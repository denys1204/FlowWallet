package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown for an action the {@code payouts} endpoint does not know. The detail never quotes it.
 * See docs/adr/0026-problem-details-never-quote-rejected-input.md.
 */
public class UnknownPayoutActionException extends ApiException {
    public UnknownPayoutActionException() {
        super(HttpStatus.BAD_REQUEST, "The action must be requeue, return-without-reversal or reverse.");
    }
}
