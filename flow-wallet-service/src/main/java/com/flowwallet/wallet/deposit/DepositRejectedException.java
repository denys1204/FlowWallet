package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service refused the request itself: the amount is outside the bounds it enforces, or carries more
 * decimal places than the currency accepts. Maps to HTTP 400.
 * <p>
 * The detail is Payment Service's own wording, because only that service knows the bounds.
 * See docs/adr/0013-deposit-initiation.md.
 */
public class DepositRejectedException extends ApiException {
    public DepositRejectedException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
