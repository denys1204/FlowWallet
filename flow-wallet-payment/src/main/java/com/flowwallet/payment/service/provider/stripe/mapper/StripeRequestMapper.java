package com.flowwallet.payment.service.provider.stripe.mapper;

import com.flowwallet.payment.entity.PaymentTransaction;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Component;

@Component
public class StripeRequestMapper {
    public PaymentIntentCreateParams toPaymentIntentParams(PaymentTransaction transaction) {
        // Stripe uses smallest currency unit (e.g., cents for USD, pence for GBP)
        long amountInCents = transaction.getAmount().movePointRight(2).longValue();

        return PaymentIntentCreateParams.builder()
                .setAmount(amountInCents)
                .setCurrency(transaction.getCurrency().toLowerCase())
                .putMetadata("transactionReference", transaction.getTransactionReference())
                .putMetadata("walletId", String.valueOf(transaction.getWalletId()))
                .putMetadata("userId", transaction.getUserId())
                .build();
    }
}
