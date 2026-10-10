package com.flowwallet.payment.payout;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * Thrown when an operator's action cannot be decided yet: a Stripe request of the payout's last try may still be
 * running, or the row changed while Stripe was asked. Nothing was changed, and a retry is safe.
 * See docs/adr/0016-error-model-and-status-codes.md and
 * docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
public class PayoutActionBusyException extends ApiException {
    private PayoutActionBusyException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }

    static PayoutActionBusyException quietPeriod(Instant since, Instant retryAfter) {
        return new PayoutActionBusyException(
                "The payout entered NEEDS_ATTENTION at " + since + ", and a Stripe request of its last try may still "
                        + "be running. Nothing was changed; retry after " + retryAfter + "."
        );
    }

    static PayoutActionBusyException changed() {
        return new PayoutActionBusyException(
                "The payout changed while Stripe was asked. Nothing was changed; read it again."
        );
    }
}
