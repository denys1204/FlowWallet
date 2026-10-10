package com.flowwallet.payment.payout;

import org.jspecify.annotations.Nullable;

/**
 * What became of an operator's write, as the store saw it under the row's lock.
 *
 * @param payout the row after the write, or as it stands when nothing was written; null when no row was found
 */
record OperatorWrite(Kind kind, @Nullable PayoutSnapshot payout) {
    enum Kind {
        APPLIED,
        NOT_FOUND,

        /**
         * The row is not in {@code NEEDS_ATTENTION}, so it has no operator action.
         */
        NOT_WAITING,

        /**
         * The row's {@code version} moved after it was read for the Stripe check, so the check described another
         * state and nothing was written.
         */
        CHANGED
    }

    static OperatorWrite notFound() {
        return new OperatorWrite(Kind.NOT_FOUND, null);
    }
}
