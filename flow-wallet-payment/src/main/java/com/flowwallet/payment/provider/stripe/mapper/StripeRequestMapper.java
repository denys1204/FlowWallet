package com.flowwallet.payment.provider.stripe.mapper;

import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.InvalidPaymentRequestException;
import com.flowwallet.payment.provider.stripe.StripeChargeLimits;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules.CurrencyRule;
import com.flowwallet.payment.provider.stripe.config.StripePaymentMethodType;
import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static com.flowwallet.payment.provider.stripe.StripeConstants.META_TRANSACTION_REF;

@Component
@RequiredArgsConstructor
public class StripeRequestMapper {
    private final StripeProperties properties;

    public PaymentIntentCreateParams toPaymentIntentParams(PaymentRequestContext context) {
        validate(context);

        String currencyLower = context.currency().toLowerCase(Locale.ROOT);
        long amountInSmallestUnit = toSmallestCurrencyUnit(context.amount(), context.currency());

        List<String> paymentMethods = properties.getPaymentMethodTypes().stream()
                .map(StripePaymentMethodType::apiValue)
                .toList();

        // The user id never reaches Stripe (docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md); the
        // reference is enough to trace a payment in the Stripe dashboard back to this service's own record of it.
        return PaymentIntentCreateParams.builder()
                .setAmount(amountInSmallestUnit)
                .setCurrency(currencyLower)
                // Listing the methods leaves automatic payment methods off.
                // See docs/adr/0028-deposits-accept-cards-only.md.
                .addAllPaymentMethodType(paymentMethods)
                .putMetadata(META_TRANSACTION_REF, context.transactionReference())
                .build();
    }

    /**
     * Everything this service knows Stripe would refuse, checked without a network call: the request's shape, a
     * currency Stripe does not charge, an amount finer than the currency's grid and one below Stripe's minimum
     * charge. The strategy runs it before the transaction row is reserved, so a refusal leaves the reference free.
     * See docs/adr/0013-deposit-initiation.md and docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md.
     */
    public void validate(PaymentRequestContext context) {
        if (context.amount() == null || context.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPaymentRequestException(
                    "Payment amount must be positive, got: " + context.amount()
            );
        }
        if (context.currency() == null || context.currency().isBlank()) {
            throw new InvalidPaymentRequestException("Payment currency must not be blank");
        }
        if (context.transactionReference() == null || context.transactionReference().isBlank()) {
            throw new InvalidPaymentRequestException("Transaction reference must not be blank");
        }

        if (!StripeChargeLimits.isChargeable(context.currency())) {
            throw new InvalidPaymentRequestException("Stripe does not charge in " + context.currency());
        }

        CurrencyRule rule = StripeCurrencyRules.of(context.currency());
        int scale = context.amount().stripTrailingZeros().scale();
        if (scale > rule.acceptedScale()) {
            throw new InvalidPaymentRequestException(
                    "Payment amount %s carries more decimal places than %s accepts (at most %d)"
                            .formatted(context.amount().toPlainString(), context.currency(), rule.acceptedScale())
            );
        }

        StripeChargeLimits.minimumCharge(context.currency())
                .filter(minimum -> context.amount().compareTo(minimum) < 0)
                .ifPresent(minimum -> {
                    throw new InvalidPaymentRequestException(
                            "Minimum deposit amount in %s is %s, the smallest charge Stripe accepts in it"
                                    .formatted(context.currency(), minimum.toPlainString())
                    );
                });
    }

    /**
     * Converts a major-unit amount to the unit Stripe charges in, without rounding. {@link #validate} has already
     * refused any amount finer than the currency accepts, so the shift is exact and
     * {@link BigDecimal#longValueExact()} can only fail on an amount too large for a long.
     * See docs/adr/0015-currency-precision-and-no-rounding.md.
     */
    private long toSmallestCurrencyUnit(BigDecimal amount, String currency) {
        return amount
                .movePointRight(StripeCurrencyRules.of(currency).transmitExponent())
                .longValueExact();
    }
}
