package com.flowwallet.common.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Client request to top up a wallet.
 * <p>
 * {@code walletId} comes from the path variable, {@code userId} from the {@code @CurrentUserId} header.
 * Currency is validated against the wallet's currency on the server side.
 *
 * @param amount the top-up amount (min 1.00, max 10,000.00)
 */
public record TopUpRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "1.00", message = "Minimum top-up amount is 1.00")
        @DecimalMax(value = "10000.00", message = "Maximum top-up amount is 10,000.00")
        BigDecimal amount
) {
}
