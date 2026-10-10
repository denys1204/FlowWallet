package com.flowwallet.payment.provider.dto;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A payout from a connected account to its bank, as the provider holds it.
 *
 * @param failureCode   the provider's failure code for a failed payout, when it gave one
 * @param reference     the withdrawal's reference from the payout's metadata; null for a payout this service did not
 *                      make
 * @param amount        in major units
 * @param currency      upper-case ISO 4217 code
 * @param destinationId the bank account the payout was sent to, which a failure is about; null when Stripe gave none
 */
public record ProviderPayout(
        String id,
        ProviderPayoutStatus status,
        @Nullable String failureCode,
        @Nullable String reference,
        BigDecimal amount,
        String currency,
        @Nullable String destinationId
) {
}
