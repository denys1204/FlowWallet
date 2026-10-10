package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import org.jspecify.annotations.Nullable;

/**
 * What applying a payout's current status at Stripe did to its row.
 *
 * @param from        the row's status before; null when no row was found
 * @param to          the row's status after; null when no row was found
 * @param bankReset   whether a failed payout set the stored bank account back to {@code NOT_SET}
 * @param differences what disagreed with the row, for {@code DIFFERING_TERMS}
 */
public record Settlement(
        Kind kind,
        @Nullable String reference,
        ProviderPayoutStatus observed,
        @Nullable PayoutStatus from,
        @Nullable PayoutStatus to,
        boolean bankReset,
        @Nullable String differences
) {
    public enum Kind {
        /**
         * The row moved: adopted, paid or sent to its reversal.
         */
        MOVED,

        /**
         * The row already agrees with Stripe, as on a redelivery.
         */
        REPEATED,

        /**
         * The status cannot move the row, such as {@code paid} on a payout already reversing.
         */
        CONTRADICTED,

        /**
         * The payout's account, amount, currency, id or reference differ from the row's; nothing is written.
         */
        DIFFERING_TERMS,

        /**
         * No row holds the payout.
         */
        UNKNOWN_PAYOUT
    }
}
