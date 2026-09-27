package com.flowwallet.payment.webhook;

import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.WebhookEventType;
import com.flowwallet.payment.provider.dto.WebhookResult;
import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.transaction.PaymentTransactionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentWebhookServiceTest {
    private static final Map<String, String> HEADERS = Map.of("Stripe-Signature", "t=1,v1=00");

    private final PaymentTransactionHandler transactionHandler = mock(PaymentTransactionHandler.class);
    private final PaymentProviderFactory factory = mock(PaymentProviderFactory.class);
    private final PaymentProviderStrategy strategy = mock(PaymentProviderStrategy.class);
    private final PaymentWebhookService service = new PaymentWebhookService(transactionHandler, factory);

    @BeforeEach
    void setUp() {
        when(factory.getStrategy("stripe")).thenReturn(strategy);
    }

    @Test
    void aSuccessEventIsHandedToTheSuccessPathOnly() {
        // A swapped branch would settle a failed payment or fail a paid one.
        WebhookResult result = result(WebhookEventType.PAYMENT_SUCCESS);
        when(strategy.handleWebhook("payload", HEADERS)).thenReturn(result);

        service.processWebhook("stripe", "payload", HEADERS);

        verify(transactionHandler).handleSuccess(result);
        verify(transactionHandler, never()).handleFailure(any());
    }

    @Test
    void aFailureEventIsHandedToTheFailurePathOnly() {
        WebhookResult result = result(WebhookEventType.PAYMENT_FAILURE);
        when(strategy.handleWebhook("payload", HEADERS)).thenReturn(result);

        service.processWebhook("stripe", "payload", HEADERS);

        verify(transactionHandler).handleFailure(result);
        verify(transactionHandler, never()).handleSuccess(any());
    }

    @Test
    void anUnknownEventTouchesNoTransaction() {
        // Stripe sends event types this service does not subscribe to handle; they must change nothing.
        when(strategy.handleWebhook("payload", HEADERS)).thenReturn(WebhookResult.unknown());

        service.processWebhook("stripe", "payload", HEADERS);

        verifyNoInteractions(transactionHandler);
    }

    @Test
    void aRefusedWebhookTouchesNoTransaction() {
        // The strategy verifies the signature; its refusal must stop the request before any row is looked up.
        when(strategy.handleWebhook("payload", HEADERS)).thenThrow(
                new InvalidWebhookSignatureException("Invalid Stripe signature")
        );

        assertThatThrownBy(() -> service.processWebhook("stripe", "payload", HEADERS))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        verifyNoInteractions(transactionHandler);
    }

    private static WebhookResult result(WebhookEventType type) {
        return new WebhookResult(
                "pi_1",
                "evt_1",
                type,
                new BigDecimal("50.00"),
                "USD",
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
