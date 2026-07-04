package com.flowwallet.payment.provider.dto;

import java.math.BigDecimal;

public record PaymentRequestContext(
        String transactionReference,
        BigDecimal amount,
        String currency,
        Long walletId,
        String userId
) {}
