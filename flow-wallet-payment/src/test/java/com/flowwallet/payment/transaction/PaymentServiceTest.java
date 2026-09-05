package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private PaymentProviderFactory factory;
    private PaymentTransactionStore store;
    private PaymentEventMapper mapper;
    private PaymentProviderStrategy strategy;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        factory = mock(PaymentProviderFactory.class);
        store = mock(PaymentTransactionStore.class);
        mapper = mock(PaymentEventMapper.class);
        strategy = mock(PaymentProviderStrategy.class);
        service = new PaymentService(factory, store, mapper);
    }

    @Test
    void returnsExistingIntentForIdempotentRetryWithoutCallingProvider() {
        PaymentTransaction existing = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_1"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(existing));
        when(mapper.toResponse(existing)).thenReturn(mapped);

        PaymentIntentResponse response = service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(response).isSameAs(mapped);
        verify(factory, never()).getStrategy(any());
        verify(store, never()).reserve(any(), any());
    }

    @Test
    void propagatesConflictWhenReferenceOwnedByAnotherUser() {
        when(store.findOwnedBy("ref-1", "user-1")).thenThrow(
                DuplicateTransactionReferenceException.forReference("ref-1")
        );

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(DuplicateTransactionReferenceException.class);
        verify(factory, never()).getStrategy(any());
        verify(store, never()).reserve(any(), any());
    }

    @Test
    void propagatesConflictOnConcurrentReserve() {
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        when(factory.getStrategy("STRIPE")).thenReturn(strategy);
        when(store.reserve(any(), eq("user-1"))).thenThrow(
                DuplicateTransactionReferenceException.forReference("ref-1")
        );

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(DuplicateTransactionReferenceException.class);
        verify(strategy, never()).initiatePayment(any());
    }

    @Test
    void createsTransactionThenCallsProviderAndReturnsMappedResponse() {
        PaymentTransaction reserved = PaymentTransaction.create(request("ref-1", "STRIPE"), "user-1");
        PaymentTransaction initiated = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_new"));
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_new"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        when(factory.getStrategy("STRIPE")).thenReturn(strategy);
        when(store.reserve(any(), eq("user-1"))).thenReturn(reserved);
        when(mapper.toRequestContext(reserved)).thenReturn(
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", "user-1")
        );
        when(strategy.initiatePayment(any())).thenReturn(
                new PaymentInitiationResult("pi_123", Map.of("clientSecret", "cs_new"))
        );
        when(store.recordInitiation(any(), eq("pi_123"), any())).thenReturn(initiated);
        when(mapper.toResponse(initiated)).thenReturn(mapped);

        PaymentIntentResponse response = service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(response).isSameAs(mapped);
        verify(store).reserve(any(), eq("user-1"));
        verify(store).recordInitiation(any(), eq("pi_123"), any());
    }

    @Test
    void unknownProviderFailsFastWithoutReserving() {
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        when(factory.getStrategy("FOO")).thenThrow(
                new UnsupportedPaymentProviderException("Unsupported payment provider: FOO")
        );

        assertThatThrownBy(
                () -> service.initiatePayment(request("ref-1", "FOO"), "user-1")
        ).isInstanceOf(UnsupportedPaymentProviderException.class);

        verify(store, never()).reserve(any(), any());
    }

    private CreatePaymentIntentRequest request(String reference, String provider) {
        return new CreatePaymentIntentRequest(
                reference,
                new BigDecimal("50.00"),
                "USD",
                provider
        );
    }

    private PaymentTransaction initiatedTransaction(String providerTransactionId, Map<String, Object> metadata) {
        PaymentTransaction tx = PaymentTransaction.create(request("ref-1", "STRIPE"), "user-1");
        tx.markAsInitiated(providerTransactionId, metadata);
        return tx;
    }
}
