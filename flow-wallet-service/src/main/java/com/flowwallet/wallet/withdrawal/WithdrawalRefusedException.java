package com.flowwallet.wallet.withdrawal;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The withdrawal was refused and the refusal recorded under its key. Maps to HTTP 422.
 * <p>
 * Unlike a transfer's 422, this one spends the key: the same key keeps getting this answer, even after a top-up or a
 * bank account set later, so a client's automatic retry never pays out a withdrawal the user saw refused.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public class WithdrawalRefusedException extends ApiException {
    public WithdrawalRefusedException(WithdrawalRefusalReason reason, String currency) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, detail(reason, currency));
    }

    private static String detail(WithdrawalRefusalReason reason, String currency) {
        return switch (reason) {
            case INSUFFICIENT_FUNDS -> "Insufficient funds in the " + currency + " wallet. This Idempotency-Key is "
                    + "spent; withdraw a smaller amount or top up, then use a new key.";
            case NO_BANK_ACCOUNT -> "No bank account is ready for payouts in " + currency + ". This Idempotency-Key "
                    + "is spent; set a bank account, then use a new key.";
        };
    }
}
