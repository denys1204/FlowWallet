package com.flowwallet.payment.provider.stripe.config;

import com.stripe.param.PaymentIntentCreateParams;

/**
 * A payment method a deposit may be paid with, carrying the SDK's value for it. Only cards are listed, so
 * configuration that names any other method fails at startup.
 * See docs/adr/0028-deposits-accept-cards-only.md.
 */
public enum StripePaymentMethodType {
    CARD(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD);

    private final PaymentIntentCreateParams.AllowedPaymentMethodType allowed;

    StripePaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType allowed) {
        this.allowed = allowed;
    }

    /**
     * The method as a PaymentIntent's {@code allowed_payment_method_types} takes it.
     */
    public PaymentIntentCreateParams.AllowedPaymentMethodType allowedType() {
        return allowed;
    }
}
