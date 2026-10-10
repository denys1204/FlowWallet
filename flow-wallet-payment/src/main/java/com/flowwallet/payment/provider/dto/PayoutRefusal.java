package com.flowwallet.payment.provider.dto;

/**
 * Why a provider refused a payout step for certain. The step decides what each one means for the payout.
 */
public enum PayoutRefusal {
    /**
     * The source balance lacks the funds: the platform's for a transfer, the connected account's for a payout or a
     * reversal.
     */
    INSUFFICIENT_FUNDS,

    /**
     * The bank account, or the connected account's payouts, cannot be used.
     */
    BANK_ACCOUNT,

    /**
     * A limit the provider resets later, such as the daily payout limit. The step tries again under a fresh key.
     */
    LIMIT_REACHED,

    /**
     * Any other refusal the provider makes definite.
     */
    OTHER
}
