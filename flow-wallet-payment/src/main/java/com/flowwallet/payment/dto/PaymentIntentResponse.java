package com.flowwallet.payment.dto;

import java.util.Map;

/**
 * Returned once a payment has been started, carrying whatever the client needs to finish it with the
 * provider. Each provider strategy fills {@code providerData} key by key and never copies a provider response
 * into it wholesale. See docs/adr/0013-deposit-initiation.md.
 * <ul>
 *   <li>{@code STRIPE} — {@code clientSecret}, which the frontend hands to Stripe.js.</li>
 * </ul>
 *
 * @param providerData         provider-specific data needed to complete the payment; see above
 * @param paymentIntentId      the provider's own id for this payment
 * @param transactionReference reference that links this payment across services
 */
public record PaymentIntentResponse(
        Map<String, Object> providerData,
        String paymentIntentId,
        String transactionReference
) {}
