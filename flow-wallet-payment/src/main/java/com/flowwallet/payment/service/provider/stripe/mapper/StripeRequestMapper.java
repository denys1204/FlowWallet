package com.flowwallet.payment.service.provider.stripe.mapper;

import com.flowwallet.payment.service.provider.PaymentRequestContext;
import com.stripe.param.PaymentIntentCreateParams;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class StripeRequestMapper {
    private static final BigDecimal CENTS_MULTIPLIER = BigDecimal.valueOf(100);

    public PaymentIntentCreateParams toPaymentIntentParams(PaymentRequestContext context) {
        // Stripe uses smallest currency unit (e.g., cents for USD, pence for GBP)
        long amountInCents = context.amount().multiply(CENTS_MULTIPLIER).longValue();

        return PaymentIntentCreateParams.builder()
                .setAmount(amountInCents)
                .setCurrency(context.currency().toLowerCase())
                .putMetadata("transactionReference", context.transactionReference())
                .putMetadata("walletId", String.valueOf(context.walletId()))
                .putMetadata("userId", context.userId())
                .build();
    }
}
