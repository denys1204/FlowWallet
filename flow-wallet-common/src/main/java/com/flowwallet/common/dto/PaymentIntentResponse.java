package com.flowwallet.common.dto;

/**
 * Response returned to the client after initiating a top-up.
 * Contains the Stripe {@code client_secret} needed to complete payment on the frontend.
 *
 * @param clientSecret         Stripe client secret for frontend payment confirmation
 * @param paymentIntentId      Stripe PaymentIntent ID (pi_xxx)
 * @param transactionReference unique reference linking this payment across services
 */
public record PaymentIntentResponse(
        String clientSecret,
        String paymentIntentId,
        String transactionReference
) {
}
