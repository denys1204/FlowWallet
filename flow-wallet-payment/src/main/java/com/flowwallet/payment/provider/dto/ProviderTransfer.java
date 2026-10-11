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
    /**
     * Whether nothing of the transfer stays in the connected account: its reversals reach its own amount. The transfer
     * step ignores such a transfer, and so does a return without reversal.
     * See docs/adr/0045-a-transfer-reversed-in-full-is-ignored-and-a-spent-key-moves-on.md.
     */
    public boolean reversedInFull() {
        return amountReversed.compareTo(amount) >= 0;
    }
}
