package com.flowwallet.payment.provider.stripe.mapper;

import static com.flowwallet.payment.provider.stripe.StripeConstants.CENTS_MULTIPLIER;
import static com.flowwallet.payment.provider.stripe.StripeConstants.META_TRANSACTION_REF;
import static com.flowwallet.payment.provider.stripe.StripeConstants.META_USER_ID;
import static com.flowwallet.payment.provider.stripe.StripeConstants.META_WALLET_ID;
import static com.flowwallet.payment.provider.stripe.StripeConstants.ZERO_DECIMAL_CURRENCIES;

import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.InvalidPaymentRequestException;
import com.stripe.param.PaymentIntentCreateParams;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class StripeRequestMapper {
    public PaymentIntentCreateParams toPaymentIntentParams(PaymentRequestContext context) {
        validateContext(context);

        String currencyLower = context.currency().toLowerCase();
        long amountInSmallestUnit = toSmallestCurrencyUnit(context.amount(), context.currency());

        var paymentMethods = PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                .setEnabled(true)
                .build();

        return PaymentIntentCreateParams.builder()
                .setAmount(amountInSmallestUnit)
                .setCurrency(currencyLower)
                .setAutomaticPaymentMethods(paymentMethods)
                .putMetadata(META_TRANSACTION_REF, context.transactionReference())
                .putMetadata(META_WALLET_ID, String.valueOf(context.walletId()))
                .putMetadata(META_USER_ID, context.userId())
                .build();
    }

    /**
     * Converts a monetary amount to the smallest currency unit expected by Stripe.
     * For most currencies this means multiplying by 100 (e.g. USD → cents).
     * For zero-decimal currencies (e.g. JPY) the amount is used directly.
     *
     * <p>Uses explicit rounding ({@link RoundingMode#HALF_UP}) and
     * {@link BigDecimal#longValueExact()} to prevent silent precision loss.</p>
     */
    private long toSmallestCurrencyUnit(BigDecimal amount, String currency) {
        BigDecimal converted = ZERO_DECIMAL_CURRENCIES.contains(currency.toUpperCase())
                ? amount
                : amount.multiply(CENTS_MULTIPLIER);

        return converted
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private void validateContext(PaymentRequestContext context) {
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
    }
}
