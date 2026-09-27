package com.flowwallet.payment.provider.stripe;

import com.stripe.model.StripeObject;

import java.time.Instant;

/**
 * A Stripe webhook whose signature has been verified, with its data object deserialized (e.g. a PaymentIntent).
 *
 * @param created when Stripe created the event, which for a payment outcome is when Stripe settled it
 */
public record ParsedStripeEvent(String eventId, String eventType, Instant created, StripeObject dataObject) {
}
