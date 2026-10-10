package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.api.AmountPrecision;
import org.jspecify.annotations.Nullable;

/**
 * Where a withdrawal stands.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 *
 * @param status {@code PENDING}, {@code PAID}, {@code RETURN_PENDING}, {@code RETURNED} or {@code REFUSED}
 * @param reason why a {@code REFUSED} one was refused, or why the money of a {@code RETURN_PENDING} or
 *               {@code RETURNED} one comes back, from an open set a client shows and must not fail on; null otherwise
 */
public record WithdrawalStatusResponse(
        String reference,
        String amount,
        String currency,
        String status,
        @Nullable String reason
) {
    /**
     * The reason is the refusal reason of a refused withdrawal and the return reason of one on its way back. The
     * schema's CHECKs set exactly one of them for those statuses and neither for the others.
     */
    public static WithdrawalStatusResponse of(Withdrawal withdrawal, String currency) {
        String reason = withdrawal.getRefusalReason() != null
                ? withdrawal.getRefusalReason().name()
                : withdrawal.getReturnReason();
        return new WithdrawalStatusResponse(
                withdrawal.getTransactionReference(),
                AmountPrecision.render(withdrawal.getAmount(), currency),
                currency,
                withdrawal.getStatus().name(),
                reason
        );
    }
}
