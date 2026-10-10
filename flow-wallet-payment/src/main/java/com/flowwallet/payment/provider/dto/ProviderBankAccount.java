package com.flowwallet.payment.provider.dto;

/**
 * A bank account attached to a connected account as the default for its currency.
 *
 * @param currency upper-case ISO 4217 code
 * @param ready    whether a payout can go to it now: the connected account has payouts enabled, the bank account is
 *                 the default for its currency, and its status is one Stripe pays out to
 */
public record ProviderBankAccount(
        String externalAccountId,
        String last4,
        String currency,
        boolean ready
) {
}
