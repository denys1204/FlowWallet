package com.flowwallet.wallet.withdrawal;

/**
 * Where a withdrawal stands, as its owner sees it. {@code PENDING}, {@code PAID}, {@code RETURN_PENDING} and
 * {@code RETURNED} are ranked in that order, and a payout event only moves a withdrawal up the ranking, so an event
 * that arrives late cannot undo a later status. {@code REFUSED} is a request the wallet turned down: it never reaches
 * Payment Service and never changes.
 * See docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md.
 */
public enum WithdrawalStatus {
    /**
     * Debited and on its way to the bank.
     */
    PENDING(0),
    /**
     * Stripe reports the payout paid. Not final: a payout can fail days after it shows paid.
     */
    PAID(1),
    /**
     * The payout failed or could not be made, and the money is on its way back.
     */
    RETURN_PENDING(2),
    /**
     * The money is back in the wallet.
     */
    RETURNED(3),
    /**
     * Turned down before any debit, with the reason recorded under the key.
     */
    REFUSED(-1);

    private final int rank;

    WithdrawalStatus(int rank) {
        this.rank = rank;
    }

    boolean outranks(WithdrawalStatus other) {
        return rank > other.rank;
    }
}
