package com.flowwallet.payment.support;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;

/**
 * Stripe objects and webhook bodies as the Stripe API returns them, for stubbing {@code StripeClient} and posting
 * webhooks. Amounts are in Stripe's minor units.
 */
public final class StripeFixtures {
    private StripeFixtures() {
    }

    public static PaymentIntent paymentIntent(String id, String status, long amount, String currency) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setObject("payment_intent");
        intent.setStatus(status);
        intent.setAmount(amount);
        intent.setCurrency(currency);
        intent.setClientSecret(id + "_secret_integration-test");
        return intent;
    }

    /**
     * A {@code payment_intent.*} event at the API version the SDK pins, so the parser takes no version detour.
     *
     * @param created the event's {@code created}, in epoch seconds
     */
    public static String paymentIntentEvent(
            String eventId,
            String type,
            long created,
            String intentId,
            String status,
            long amount,
            String currency
    ) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "created": %d,
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "payment_intent",
                      "amount": %d,
                      "currency": "%s",
                      "status": "%s"
                    }
                  }
                }
                """.formatted(eventId, Stripe.API_VERSION, created, type, intentId, amount, currency, status);
    }
}
