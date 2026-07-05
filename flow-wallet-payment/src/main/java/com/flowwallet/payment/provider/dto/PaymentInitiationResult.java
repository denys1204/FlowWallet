package com.flowwallet.payment.provider.dto;

import java.util.Map;

public record PaymentInitiationResult(
        String providerTransactionId,
        Map<String, Object> providerData
) {}
