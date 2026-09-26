package com.flowwallet.wallet.deposit;

import java.util.Map;

/**
 * What Payment Service answers: a copy of its {@code PaymentIntentResponse}. {@code providerData} is opaque and
 * reaches the client untouched. See docs/adr/0013-deposit-initiation.md.
 */
record PaymentIntentResult(
        Map<String, Object> providerData,
        String paymentIntentId,
        String transactionReference
) {}
