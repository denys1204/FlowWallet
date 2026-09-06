package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service refused the request itself — the amount is outside the bounds it enforces, or carries more
 * decimal places than the currency accepts. Maps to HTTP 400.
 * <p>
 * Its wording is relayed rather than reinvented, because it names the actual bounds and the wallet does not
 * know them. Duplicating them here would be a second source of truth that drifts from the first, which is
 * the reason {@code DepositRequest} refuses to carry them either.
 */
public class DepositRejectedException extends ApiException {
    public DepositRejectedException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
