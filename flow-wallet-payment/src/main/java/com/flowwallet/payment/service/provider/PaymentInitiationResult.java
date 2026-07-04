package com.flowwallet.payment.service.provider;

public record PaymentInitiationResult(
        String providerTransactionId,
        String clientSecret
) {}
