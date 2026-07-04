package com.flowwallet.payment.provider.dto;

public record PaymentInitiationResult(
        String providerTransactionId,
        String clientSecret
) {}
