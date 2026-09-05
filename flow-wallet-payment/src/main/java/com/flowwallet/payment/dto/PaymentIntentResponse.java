package com.flowwallet.payment.dto;

import java.util.Map;

/**
 * Returned once a payment has been started, carrying whatever the client needs to finish it with the
 * provider.
 * <p>
 * {@code providerData} is a map rather than typed fields because each provider needs something
 * different, and flattening every provider's keys into one record would grow a field per provider that
 * is null for all the others. The contents are assembled explicitly by each provider strategy, never
 * copied wholesale from a provider response, so nothing leaks by accident. Today:
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
