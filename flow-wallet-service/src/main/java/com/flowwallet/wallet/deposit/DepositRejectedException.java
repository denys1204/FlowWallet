package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Payment Service refused the request: the amount is outside the bounds it enforces, carries more decimal places
 * than the currency accepts or is below the provider's minimum charge, the provider does not charge the currency,
 * or the provider itself refused the payment. Maps to HTTP 400.
 * <p>
 * The detail is Payment Service's own wording, because only that service knows the bounds, and only it knows
 * whether the key is still free. See docs/adr/0013-deposit-initiation.md and
 * docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md.
 */
public class DepositRejectedException extends ApiException {
    public DepositRejectedException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
