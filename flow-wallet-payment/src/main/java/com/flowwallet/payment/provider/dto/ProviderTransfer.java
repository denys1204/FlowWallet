package com.flowwallet.payment.provider.dto;

import java.math.BigDecimal;

/**
 * A transfer from the platform to a connected account, as the provider holds it.
 *
 * @param amount         in major units
 * @param amountReversed the part already brought back, in major units
 * @param currency       upper-case ISO 4217 code
 */
public record ProviderTransfer(
        String id,
        String destinationAccountId,
        BigDecimal amount,
        BigDecimal amountReversed,
        String currency
) {
}
