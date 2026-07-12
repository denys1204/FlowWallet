package com.flowwallet.payment.provider.stripe;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Internal constants for the Stripe payment provider integration.
 */
public final class StripeConstants {
    private StripeConstants() {
        // utility class — no instantiation
    }

    /** Multiplier used to convert major currency units to their smallest subunit (e.g. USD → cents). */
    public static final BigDecimal CENTS_MULTIPLIER = BigDecimal.valueOf(100);

    // ── Metadata keys ────────────────────────────────────────────────────

    /** Metadata key for the internal transaction reference. */
    public static final String META_TRANSACTION_REF = "transactionReference";

    /** Metadata key for the wallet ID. */
    public static final String META_WALLET_ID = "walletId";

    /** Metadata key for the user ID. */
    public static final String META_USER_ID = "userId";

    // ── Zero-decimal currencies ──────────────────────────────────────────

    /**
     * Currencies where the base unit has no fractional subunit,
     * so the amount is passed to Stripe as-is without multiplication.
     *
     * @see <a href="https://docs.stripe.com/currencies#zero-decimal">Stripe: Zero-decimal currencies</a>
     */
    public static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF"
    );

    // ── Webhook event types ──────────────────────────────────────────────

    /** Stripe event fired when a PaymentIntent is successfully completed. */
    public static final String EVENT_PAYMENT_SUCCEEDED = "payment_intent.succeeded";

    /** Stripe event fired when a PaymentIntent payment attempt fails. */
    public static final String EVENT_PAYMENT_FAILED = "payment_intent.payment_failed";

    // ── Headers ──────────────────────────────────────────────────────────

    /** HTTP header carrying the Stripe webhook signature. */
    public static final String HEADER_SIGNATURE = "stripe-signature";

    // ── Response keys ────────────────────────────────────────────────────

    /** Key used in the provider metadata map for the Stripe client secret. */
    public static final String RESPONSE_CLIENT_SECRET = "clientSecret";
}
