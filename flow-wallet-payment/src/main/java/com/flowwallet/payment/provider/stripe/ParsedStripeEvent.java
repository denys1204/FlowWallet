package com.flowwallet.payment.provider.stripe;

import com.stripe.model.StripeObject;

/**
 * A Stripe webhook whose signature has been verified, with its data object deserialized (e.g. a PaymentIntent).
 */
public record ParsedStripeEvent(String eventId, String eventType, StripeObject dataObject) {
}
