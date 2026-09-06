package com.flowwallet.wallet.deposit;

import java.util.Map;

/**
 * What Payment Service answers. {@code providerData} stays an opaque map and is copied through untouched —
 * flattening it into a {@code clientSecret} field would bake one provider's vocabulary into the wallet's
 * public contract.
 */
record PaymentIntentResult(
        Map<String, Object> providerData,
        String paymentIntentId,
        String transactionReference
) {}
