package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import com.flowwallet.payment.provider.exception.PaymentInitiationException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.flowwallet.payment.provider.stripe.mapper.StripeRequestMapper;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StripePaymentStrategyTest {
    private final StripeRequestMapper requestMapper = mock(StripeRequestMapper.class);
    private final StripeClient stripeClient = mock(StripeClient.class);
    private final StripeWebhookParser webhookParser = mock(StripeWebhookParser.class);
    private final StripePaymentStrategy strategy = new StripePaymentStrategy(
            requestMapper,
            webhookParser,
            stripeClient
    );

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

    @Test
    void wrapsStripeExceptionAsPaymentInitiationException() throws Exception {
        PaymentRequestContext context =
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", 1L, "user-1");
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(5000L)
                .setCurrency("usd")
                .build();
        when(requestMapper.toPaymentIntentParams(context)).thenReturn(params);

        StripeException stripeException = new ApiConnectionException("stripe unreachable");
        when(stripeClient.createPaymentIntent(params, "ref-1")).thenThrow(stripeException);

        assertThatThrownBy(() -> strategy.initiatePayment(context))
                .isInstanceOf(PaymentInitiationException.class)
                .hasCause(stripeException);
    }

    @Test
    void mapsSucceededWebhookToPaymentSuccessResult() {
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(paymentIntent.getId()).thenReturn("pi_1");
        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_1", "payment_intent.succeeded", paymentIntent)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.PAYMENT_SUCCESS);
        assertThat(result.providerTransactionId()).isEqualTo("pi_1");
        assertThat(result.providerEventId()).isEqualTo("evt_1");
    }

    @Test
    void returnsUnknownForNonPaymentIntentObject() {
        StripeObject other = mock(StripeObject.class);
        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_2", "charge.refunded", other)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.UNKNOWN);
    }

    @Test
    void returnsUnknownForUnhandledEventType() {
        PaymentIntent paymentIntent = mock(PaymentIntent.class);

        when(webhookParser.parse("payload", Map.of())).thenReturn(
                new ParsedStripeEvent("evt_3", "payment_intent.created", paymentIntent)
        );

        WebhookResult result = strategy.handleWebhook("payload", Map.of());

        assertThat(result.eventType()).isEqualTo(WebhookEventType.UNKNOWN);
    }
}
