package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderTransfer;
import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the payout's state or what Stripe holds refuses an operator's action. Nothing was changed, and the
 * detail names the action to use instead, Stripe's ids and statuses, and never a user id.
 * See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
public class PayoutActionRefusedException extends ApiException {
    private PayoutActionRefusedException(String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, detail);
    }

    static PayoutActionRefusedException notWaiting(PayoutSnapshot payout) {
        String returned = payout.status() == PayoutStatus.RETURNED && payout.reasonCode() != null
                ? " It was returned with " + payout.reasonCode() + "."
                : "";
        return new PayoutActionRefusedException(
                "The payout is " + payout.status() + ", not NEEDS_ATTENTION; nothing was changed." + returned
        );
    }

    static PayoutActionRefusedException transferStillOut(PayoutSnapshot payout) {
        return new PayoutActionRefusedException(
                "Transfer " + payout.stripeTransferId() + " sits in connected account " + payout.stripeAccountId()
                        + ", and a reversal can still bring it back. Use reverse once no payout of it may reach the "
                        + "bank, or requeue."
        );
    }

    static PayoutActionRefusedException nothingTransferred() {
        return new PayoutActionRefusedException("Nothing was transferred. Use requeue or return-without-reversal.");
    }

    static PayoutActionRefusedException alreadyReversing() {
        return new PayoutActionRefusedException("The payout is already being reversed. Requeue retries the reversal.");
    }

    static PayoutActionRefusedException transferInGroup(ProviderTransfer transfer) {
        return new PayoutActionRefusedException(
                "Stripe holds transfer " + transfer.id() + " in the payout's group, not reversed in full. Requeue "
                        + "adopts it if it is the only one and has the payout's terms; otherwise reverse it in the "
                        + "Dashboard, then return the payout."
        );
    }

    static PayoutActionRefusedException livePayout(ProviderPayout payout) {
        return new PayoutActionRefusedException(
                "Stripe payout " + payout.id() + " for this payout is " + payout.status()
                        + " and may reach the bank; nothing was changed. Requeue once Stripe reports it failed or "
                        + "canceled; a paid one needs correction by hand."
        );
    }

    static PayoutActionRefusedException livePayoutToAdopt(ProviderPayout payout) {
        return new PayoutActionRefusedException(
                "Stripe payout " + payout.id() + " for this payout is " + payout.status() + ", has the payout's amount "
                        + "and currency, and may reach the bank; nothing was changed. Requeue, and the payout step "
                        + "adopts it."
        );
    }

    static PayoutActionRefusedException alreadyReversed(String stripeTransferId) {
        return new PayoutActionRefusedException(
                "Transfer " + stripeTransferId + " is reversed in full. Requeue, and the reversal step records it "
                        + "with the payout's own reason."
        );
    }
}
