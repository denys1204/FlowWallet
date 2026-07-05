package com.flowwallet.common.dto;

import java.util.Map;

/**
 * Response returned to the client after initiating a top-up.
 * Contains the provider-specific data needed to complete payment on the frontend.
 *
 * @param providerData         Provider-specific data (e.g. client_secret for Stripe, redirectUrl for PayPal)
 * @param paymentIntentId      Payment provider's intent ID
 * @param transactionReference unique reference linking this payment across services
 */
public record PaymentIntentResponse(
        Map<String, Object> providerData,
        String paymentIntentId,
        String transactionReference
) {
}
