package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.flowwallet.payment.provider.stripe.mapper.StripeRequestMapper;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripePaymentStrategyTest {
    private final StripeRequestMapper requestMapper = mock(StripeRequestMapper.class);
    private final StripeClient stripeClient = mock(StripeClient.class);
    private final StripePaymentStrategy strategy = new StripePaymentStrategy(requestMapper, stripeClient);

    @Test
    void usesTransactionReferenceAsStripeIdempotencyKey() throws Exception {
        PaymentRequestContext context =
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", 1L, "user-1");
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(5000L)
                .setCurrency("usd")
                .build();
        when(requestMapper.toPaymentIntentParams(context)).thenReturn(params);

        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn("pi_123");
        when(intent.getClientSecret()).thenReturn("cs_test");
        when(stripeClient.createPaymentIntent(params, "ref-1")).thenReturn(intent);

        PaymentInitiationResult result = strategy.initiatePayment(context);

        assertThat(result.providerTransactionId()).isEqualTo("pi_123");
        assertThat(result.providerData()).containsEntry("clientSecret", "cs_test");
        verify(stripeClient).createPaymentIntent(params, "ref-1");
    }
}
