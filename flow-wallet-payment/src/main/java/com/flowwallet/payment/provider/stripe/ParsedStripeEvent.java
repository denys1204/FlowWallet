package com.flowwallet.payment.provider.stripe;

import com.stripe.model.StripeObject;

/**
 * Result of verifying and parsing a Stripe webhook request: the provider event id, the raw Stripe
 * event-type string, and the deserialized data object (a {@link StripeObject}, e.g. a PaymentIntent).
 */
public record ParsedStripeEvent(String eventId, String eventType, StripeObject dataObject) {
}
