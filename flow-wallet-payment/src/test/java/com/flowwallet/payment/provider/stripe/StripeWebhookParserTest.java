package com.flowwallet.payment.provider.stripe;

import com.flowwallet.payment.provider.exception.InvalidWebhookSignatureException;
import com.flowwallet.payment.provider.exception.WebhookProcessingException;
import com.flowwallet.payment.provider.stripe.client.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeWebhookParserTest {
    private final StripeClient stripeClient = mock(StripeClient.class);
    private final StripeWebhookParser parser = new StripeWebhookParser(stripeClient);

    @Test
    void parsesSignedEventIntoParsedStripeEvent() throws Exception {
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        Event event = stripeEvent("evt_1", "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));
        when(stripeClient.constructEvent("payload", "sig")).thenReturn(event);

        ParsedStripeEvent result = parser.parse("payload", Map.of("Stripe-Signature", "sig"));

        assertThat(result.eventId()).isEqualTo("evt_1");
        assertThat(result.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(result.dataObject()).isSameAs(paymentIntent);
    }

    @Test
    void throwsWhenSignatureHeaderMissing() {
        assertThatThrownBy(() -> parser.parse("payload", Map.of()))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void throwsInvalidSignatureWhenConstructEventRejects() throws Exception {
        SignatureVerificationException verificationError = new SignatureVerificationException("bad signature", "sig");
        when(stripeClient.constructEvent("payload", "sig")).thenThrow(verificationError);

        assertThatThrownBy(() -> parser.parse("payload", Map.of("Stripe-Signature", "sig")))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasCause(verificationError);
    }

    @Test
    void fallsBackToUnsafeDeserializationOnApiVersionMismatch() throws Exception {
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        Event event = stripeEvent("evt_2", "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.deserializeUnsafe()).thenReturn(paymentIntent);
        when(stripeClient.constructEvent("payload", "sig")).thenReturn(event);

        ParsedStripeEvent result = parser.parse("payload", Map.of("Stripe-Signature", "sig"));

        assertThat(result.dataObject()).isSameAs(paymentIntent);
    }

    @Test
    void throwsWebhookProcessingWhenUnsafeDeserializationFails() throws Exception {
        Event event = stripeEvent("evt_3", "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());
        when(deserializer.deserializeUnsafe()).thenThrow(
                new EventDataObjectDeserializationException("version mismatch", null)
        );
        when(stripeClient.constructEvent("payload", "sig")).thenReturn(event);

        assertThatThrownBy(() -> parser.parse("payload", Map.of("Stripe-Signature", "sig")))
                .isInstanceOf(WebhookProcessingException.class);
    }

    private Event stripeEvent(String id, String type) {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn(id);
        when(event.getType()).thenReturn(type);
        return event;
    }
}
