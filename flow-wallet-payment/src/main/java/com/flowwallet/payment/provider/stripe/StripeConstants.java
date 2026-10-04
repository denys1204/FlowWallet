package com.flowwallet.payment.provider.stripe;

import java.util.Set;

public final class StripeConstants {
    private StripeConstants() {
    }

    public static final String META_TRANSACTION_REF = "transactionReference";

    public static final String EVENT_PAYMENT_SUCCEEDED = "payment_intent.succeeded";

    public static final String EVENT_PAYMENT_FAILED = "payment_intent.payment_failed";

    public static final String STATUS_SUCCEEDED = "succeeded";

    public static final String STATUS_CANCELED = "canceled";

    /**
     * The PaymentIntent statuses that are not settled yet: the customer can still pay, or the payment is still being
     * processed. The reconciler leaves such a payment as it is.
     */
    public static final Set<String> STATUSES_NOT_SETTLED = Set.of(
            "requires_payment_method",
            "requires_confirmation",
            "requires_action",
            "processing"
    );

    public static final String HEADER_SIGNATURE = "stripe-signature";

    public static final String RESPONSE_CLIENT_SECRET = "clientSecret";
}
