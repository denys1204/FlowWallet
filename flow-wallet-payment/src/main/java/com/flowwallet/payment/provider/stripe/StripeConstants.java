package com.flowwallet.payment.provider.stripe;


public final class StripeConstants {
    private StripeConstants() {
    }

    public static final String META_TRANSACTION_REF = "transactionReference";

    public static final String META_USER_ID = "userId";

    public static final String EVENT_PAYMENT_SUCCEEDED = "payment_intent.succeeded";

    public static final String EVENT_PAYMENT_FAILED = "payment_intent.payment_failed";

    public static final String HEADER_SIGNATURE = "stripe-signature";

    public static final String RESPONSE_CLIENT_SECRET = "clientSecret";
}
