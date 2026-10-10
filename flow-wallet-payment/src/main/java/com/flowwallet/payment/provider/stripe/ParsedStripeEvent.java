package com.flowwallet.payment.provider.stripe;

import com.stripe.model.HasId;
import com.stripe.model.StripeObject;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A Stripe webhook whose signature has been verified, with its data object deserialized (e.g. a PaymentIntent).
 *
 * @param created when Stripe created the event, which for a payment outcome is when Stripe settled it
 * @param account the connected account the event is about; null for an event on the platform itself
 */
public record ParsedStripeEvent(
        String eventId,
        String eventType,
        Instant created,
        @Nullable String account,
        StripeObject dataObject
) {
    /**
     * The id of the event's object, such as a payout's {@code po_} id; null for an object without one.
     */
    public @Nullable String objectId() {
        return dataObject instanceof HasId identified ? identified.getId() : null;
    }
}
