package com.flowwallet.payment.provider.stripe.config;

import java.util.Locale;

/**
 * A payment method a deposit may be paid with. The constant's name, lower-cased, is Stripe's name for the method.
 * Only cards are listed, so configuration that names any other method fails at startup.
 * See docs/adr/0028-deposits-accept-cards-only.md.
 */
public enum StripePaymentMethodType {
    CARD;

    /**
     * Stripe's name for the method, as {@code payment_method_types} takes it.
     */
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
