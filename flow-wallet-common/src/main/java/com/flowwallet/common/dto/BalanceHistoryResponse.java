package com.flowwallet.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response representing a single entry in the wallet's balance history.
 *
 * @param id                   history record ID
 * @param transactionReference unique reference linking to the originating transaction
 * @param type                 operation type (TOP_UP, WITHDRAWAL)
 * @param amount               operation amount
 * @param balanceBefore        balance before the operation
 * @param balanceAfter         balance after the operation
 * @param description          optional human-readable description
 * @param createdAt            when the operation was recorded
 */
public record BalanceHistoryResponse(
        Long id,
        String transactionReference,
        String type,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String description,
        Instant createdAt
) {}
