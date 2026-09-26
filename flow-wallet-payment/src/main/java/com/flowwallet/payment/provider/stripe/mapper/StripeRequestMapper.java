package com.flowwallet.payment.provider.stripe.mapper;

import static com.flowwallet.payment.provider.stripe.StripeConstants.META_TRANSACTION_REF;
import static com.flowwallet.payment.provider.stripe.StripeConstants.META_USER_ID;

import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.InvalidPaymentRequestException;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules.CurrencyRule;
import com.stripe.param.PaymentIntentCreateParams;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class StripeRequestMapper {
    public PaymentIntentCreateParams toPaymentIntentParams(PaymentRequestContext context) {
        validate(context);

        String currencyLower = context.currency().toLowerCase(Locale.ROOT);
        long amountInSmallestUnit = toSmallestCurrencyUnit(context.amount(), context.currency());

        var paymentMethods = PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                .setEnabled(true)
                .build();

        return PaymentIntentCreateParams.builder()
                .setAmount(amountInSmallestUnit)
                .setCurrency(currencyLower)
                .setAutomaticPaymentMethods(paymentMethods)
                .putMetadata(META_TRANSACTION_REF, context.transactionReference())
                .putMetadata(META_USER_ID, context.userId())
                .build();
    }

    /**
     * Everything Stripe can refuse without a network call. The strategy runs it before the transaction row is
     * reserved, so a refusal leaves the reference free. See docs/adr/0013-deposit-initiation.md.
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

        CurrencyRule rule = StripeCurrencyRules.of(context.currency());
        int scale = context.amount().stripTrailingZeros().scale();
        if (scale > rule.acceptedScale()) {
            throw new InvalidPaymentRequestException(
                    "Payment amount %s carries more decimal places than %s accepts (at most %d)"
                            .formatted(context.amount().toPlainString(), context.currency(), rule.acceptedScale())
            );
        }
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
