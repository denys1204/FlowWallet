package com.flowwallet.payment.transaction;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import com.flowwallet.common.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private PaymentTransactionRepository repository;
    private PaymentProviderFactory factory;
    private PaymentEventMapper mapper;
    private PaymentProviderStrategy strategy;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentTransactionRepository.class);
        factory = mock(PaymentProviderFactory.class);
        mapper = mock(PaymentEventMapper.class);
        strategy = mock(PaymentProviderStrategy.class);
        service = new PaymentService(repository, factory, mapper);
    }

    @Test
    void returnsExistingIntentForIdempotentRetryBySameOwnerWithoutCallingProvider() {
        PaymentTransaction existing = existingTransaction("user-1", "pi_123", Map.of("clientSecret", "cs_1"));
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.of(existing));

        PaymentIntentResponse response = service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(response.paymentIntentId()).isEqualTo("pi_123");
        assertThat(response.transactionReference()).isEqualTo("ref-1");
        assertThat(response.providerData()).containsEntry("clientSecret", "cs_1");
        verify(factory, never()).getStrategy(anyString());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsReferenceOwnedByAnotherUserWithoutLeakingProviderMetadata() {
        PaymentTransaction otherOwners = existingTransaction(
                "other-user",
                "pi_999",
                Map.of("clientSecret", "cs_secret")
        );
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.of(otherOwners));

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOfSatisfying(
                        DuplicateTransactionReferenceException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT)
                )
                .hasMessageNotContaining("cs_secret");
        verify(factory, never()).getStrategy(anyString());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void unknownProviderFailsFastWithoutWritingAnyRow() {
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.empty());
        when(factory.getStrategy("FOO")).thenThrow(
                new UnsupportedPaymentProviderException("Unsupported payment provider: FOO")
        );

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "FOO"), "user-1"))
                .isInstanceOf(UnsupportedPaymentProviderException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentInsertRaceIntoConflict() {
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.empty());
        when(factory.getStrategy("STRIPE")).thenReturn(strategy);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(DuplicateTransactionReferenceException.class);
        verify(strategy, never()).initiatePayment(any());
    }

    @Test
    void createsTransactionCallsProviderAndReturnsClientSecret() {
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.empty());
        when(factory.getStrategy("STRIPE")).thenReturn(strategy);
        PaymentTransaction saved = PaymentTransaction.create(request("ref-1", "STRIPE"), "user-1");
        when(repository.saveAndFlush(any())).thenReturn(saved);
        when(mapper.toRequestContext(saved)).thenReturn(
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", 1L, "user-1")
        );
        when(strategy.initiatePayment(any())).thenReturn(
                new PaymentInitiationResult("pi_123", Map.of("clientSecret", "cs_new"))
        );

        PaymentIntentResponse response = service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(response.paymentIntentId()).isEqualTo("pi_123");
        assertThat(response.providerData()).containsEntry("clientSecret", "cs_new");
        assertThat(response.transactionReference()).isEqualTo("ref-1");
        verify(repository).saveAndFlush(any());
        verify(repository).save(saved);
    }

    private CreatePaymentIntentRequest request(String reference, String provider) {
        return new CreatePaymentIntentRequest(
                reference,
                new BigDecimal("50.00"),
                "USD",
                1L,
                provider
        );
    }

    private PaymentTransaction existingTransaction(
            String userId,
            String providerTransactionId,
            Map<String, Object> metadata
    ) {
        PaymentTransaction tx = PaymentTransaction.create(request("ref-1", "STRIPE"), userId);
        tx.markAsInitiated(providerTransactionId, metadata);
        return tx;
    }
}
