package com.flowwallet.payment.transaction;

import com.flowwallet.common.dto.CreatePaymentIntentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentTransactionStoreTest {
    private PaymentTransactionRepository repository;
    private PaymentTransactionStore store;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentTransactionRepository.class);
        store = new PaymentTransactionStore(repository);
    }

    @Test
    void findOwnedByReturnsTransactionForItsOwner() {
        PaymentTransaction tx = transaction("user-1");
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.of(tx));

        assertThat(store.findOwnedBy("ref-1", "user-1")).contains(tx);
    }

    @Test
    void findOwnedByRejectsReferenceOwnedByAnotherUser() {
        PaymentTransaction tx = transaction("other-user");
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.of(tx));

        assertThatThrownBy(
                () -> store.findOwnedBy("ref-1", "user-1")
        ).isInstanceOf(DuplicateTransactionReferenceException.class);
    }

    @Test
    void findOwnedByReturnsEmptyWhenNoTransactionExists() {
        when(repository.findByTransactionReference("ref-1")).thenReturn(Optional.empty());

        assertThat(store.findOwnedBy("ref-1", "user-1")).isEmpty();
    }

    @Test
    void reserveReturnsSavedTransaction() {
        PaymentTransaction saved = transaction("user-1");
        when(repository.saveAndFlush(any())).thenReturn(saved);

        assertThat(store.reserve(request(), "user-1")).isSameAs(saved);
    }

    @Test
    void reserveTranslatesUniqueViolationIntoConflict() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(
                () -> store.reserve(request(), "user-1")
        ).isInstanceOf(DuplicateTransactionReferenceException.class);
    }

    @Test
    void recordInitiationLoadsMarksAndSaves() {
        PaymentTransaction tx = transaction("user-1");
        when(repository.findById(5L)).thenReturn(Optional.of(tx));
        when(repository.save(tx)).thenReturn(tx);

        PaymentTransaction result = store.recordInitiation(5L, "pi_9", Map.of("clientSecret", "cs"));

        assertThat(result.getProviderTransactionId()).isEqualTo("pi_9");
        assertThat(result.getProviderMetadata()).containsEntry("clientSecret", "cs");
        verify(repository).save(tx);
    }

    @Test
    void recordInitiationThrowsWhenTransactionMissing() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> store.recordInitiation(9L, "pi_9", Map.of())
        ).isInstanceOf(TransactionNotFoundException.class);
    }

    private CreatePaymentIntentRequest request() {
        return new CreatePaymentIntentRequest("ref-1", new BigDecimal("50.00"), "USD", 1L, "STRIPE");
    }

    private PaymentTransaction transaction(String userId) {
        return PaymentTransaction.create(request(), userId);
    }
}
