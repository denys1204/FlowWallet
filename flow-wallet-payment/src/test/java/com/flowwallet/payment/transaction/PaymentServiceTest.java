package com.flowwallet.payment.transaction;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.dto.PaymentIntentResponse;
import com.flowwallet.payment.provider.PaymentProvider;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.PaymentProviderStrategy;
import com.flowwallet.payment.provider.dto.PaymentInitiationResult;
import com.flowwallet.payment.provider.dto.PaymentRequestContext;
import com.flowwallet.payment.provider.exception.InvalidPaymentRequestException;
import com.flowwallet.payment.provider.exception.PaymentRefusedException;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

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
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        factory = mock(PaymentProviderFactory.class);
        store = mock(PaymentTransactionStore.class);
        mapper = mock(PaymentEventMapper.class);
        strategy = mock(PaymentProviderStrategy.class);
        service = new PaymentService(factory, store, mapper);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void returnsExistingIntentForIdempotentRetryWithoutCallingProvider() {
        PaymentTransaction existing = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_1"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(existing));
        when(mapper.toResponse(existing)).thenReturn(mapped);

        PaymentIntentResponse response = service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(response).isSameAs(mapped);
        verify(factory, never()).resolve(any());
        verify(store, never()).reserve(any(), any(), any());
    }

    @Test
    void providerRejectionLeavesNoReservedRow() {
        // The reference must stay free after a rejected request. If a row were written first, the client
        // could not retry with a corrected amount -- the reference would already be taken by a payment that
        // never happened.
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        strategyResolves();
        doThrow(new InvalidPaymentRequestException("amount carries more decimal places than USD accepts"))
                .when(strategy).validateRequest(any());

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(InvalidPaymentRequestException.class);

        verify(store, never()).reserve(any(), any(), any());
        verify(strategy, never()).initiatePayment(any());
    }

    @Test
    void propagatesConflictWhenReferenceOwnedByAnotherUser() {
        when(store.findOwnedBy("ref-1", "user-1")).thenThrow(
                DuplicateTransactionReferenceException.forReference("ref-1")
        );

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(DuplicateTransactionReferenceException.class);
        verify(factory, never()).resolve(any());
        verify(store, never()).reserve(any(), any(), any());
    }

    @Test
    void aRequestThatLosesTheReservationToItsOwnTwinGetsTheTwinsIntent() {
        // Guards the race loser being sent to a new key. Its twin, with the same key and terms, won the unique
        // index and initiated the payment, so a 409 would make the client pay a second time under another key.
        PaymentTransaction winner = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_1"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty(), Optional.of(winner));
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenThrow(
                new DataIntegrityViolationException("duplicate key")
        );
        when(mapper.toResponse(winner)).thenReturn(mapped);

        assertThat(service.initiatePayment(request("ref-1", "STRIPE"), "user-1")).isSameAs(mapped);
        verify(strategy, never()).initiatePayment(any());
    }

    @Test
    void aTwinThatHasNotReachedTheProviderYetIsARetryableUnavailability() {
        // Guards a 409, which sends the client to a new key and a second payment, and a second provider call,
        // which races the twin's own call under one idempotency key.
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty(), Optional.of(reservedTransaction()));
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenThrow(
                new DataIntegrityViolationException("duplicate key")
        );

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(PaymentInProgressException.class)
                .hasMessageContaining("same Idempotency-Key");
        verify(strategy, never()).initiatePayment(any());
    }

    @Test
    void aRaceLostToAPaymentOnOtherTermsIsAConflict() {
        // Guards the winning row being replayed without its terms being compared: the loser asked for another
        // amount and must not receive an intent for the winner's.
        PaymentTransaction winner = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty(), Optional.of(winner));
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenThrow(
                new DataIntegrityViolationException("duplicate key")
        );

        assertThatThrownBy(() -> service.initiatePayment(
                new CreatePaymentIntentRequest("ref-1", new BigDecimal("75.00"), "USD", "STRIPE"),
                "user-1"
        ))
                .isInstanceOf(DuplicateTransactionReferenceException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void aRaceLostToAnotherUserIsAConflict() {
        // Guards the other user's row, which carries their client secret, reaching this caller.
        when(store.findOwnedBy("ref-1", "user-1"))
                .thenReturn(Optional.empty())
                .thenThrow(DuplicateTransactionReferenceException.forReference("ref-1"));
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenThrow(
                new DataIntegrityViolationException("duplicate key")
        );

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(DuplicateTransactionReferenceException.class);
    }

    @Test
    void aViolationThatNoRowExplainsIsRethrownAsTheDefectItIs() {
        // Guards another constraint's violation being reported as a duplicate reference: with no row under the
        // reference, the reservation failed for a reason no 409 describes.
        DataIntegrityViolationException violation = new DataIntegrityViolationException("value too long");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty(), Optional.empty());
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenThrow(violation);

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1")).isSameAs(violation);
    }

    @Test
    void createsTransactionThenCallsProviderAndReturnsMappedResponse() {
        PaymentTransaction reserved = reservedTransaction();
        PaymentTransaction initiated = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_new"));
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_new"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenReturn(reserved);
        when(strategy.initiatePayment(any())).thenReturn(
                new PaymentInitiationResult("pi_123", Map.of("clientSecret", "cs_new"))
        );
        when(store.recordInitiation(any(), eq("pi_123"), any())).thenReturn(initiated);
        when(mapper.toResponse(initiated)).thenReturn(mapped);

        PaymentIntentResponse response = service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(response).isSameAs(mapped);
        verify(store).reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE));
        verify(store).recordInitiation(any(), eq("pi_123"), any());
    }

    @Test
    void unknownProviderFailsFastWithoutReserving() {
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        when(factory.resolve("FOO")).thenThrow(
                new UnsupportedPaymentProviderException("Unsupported payment provider")
        );

        assertThatThrownBy(
                () -> service.initiatePayment(request("ref-1", "FOO"), "user-1")
        ).isInstanceOf(UnsupportedPaymentProviderException.class);

        verify(store, never()).reserve(any(), any(), any());
    }

    @Test
    void reusingAReferenceOnDifferentTermsIsRefused() {
        // The retry that motivates this: a client posts EUR by mistake, notices, and re-posts the same
        // reference as USD. Without the guard it receives 200 and the original EUR payment's client secret,
        // then charges EUR believing it corrected the mistake.
        PaymentTransaction existing = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.initiatePayment(
                new CreatePaymentIntentRequest("ref-1", new BigDecimal("50.00"), "EUR", "STRIPE"), "user-1"
        ))
                .isInstanceOf(DuplicateTransactionReferenceException.class)
                .hasMessageContaining("currency");

        verify(mapper, never()).toResponse(any());
    }

    @Test
    void reusingAReferenceForADifferentAmountIsRefused() {
        PaymentTransaction existing = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.initiatePayment(
                new CreatePaymentIntentRequest("ref-1", new BigDecimal("75.00"), "USD", "STRIPE"), "user-1"
        ))
                .isInstanceOf(DuplicateTransactionReferenceException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void aGenuineRetryStillReturnsTheOriginalIntent() {
        // Same payment, spelled differently: the amount at a wider scale, the provider in lower case. Both
        // are how the request comes back from a client or a NUMERIC(19,4) column, and neither is a conflict.
        PaymentTransaction existing = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_1"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(existing));
        when(mapper.toResponse(existing)).thenReturn(mapped);

        PaymentIntentResponse response = service.initiatePayment(
                new CreatePaymentIntentRequest("ref-1", new BigDecimal("50.0000"), "USD", "stripe"), "user-1"
        );

        assertThat(response).isSameAs(mapped);
    }

    @Test
    void aReferenceThatWasAlreadyPaidIsRefused() {
        // Handing the original intent back would return a client secret that has been spent, with a 200
        // saying all is well. The client would then fail at Stripe, learning from a third party what this
        // service already knew.
        PaymentTransaction settled = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        settled.markAsSuccess("evt_1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(settled));

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(DuplicateTransactionReferenceException.class)
                .hasMessageContaining("already paid");

        verify(mapper, never()).toResponse(any());
    }

    @Test
    void aDeclinedPaymentCanStillBeRetriedUnderTheSameReference() {
        // A declined card leaves the provider's intent usable, so this is a retry rather than a finished
        // payment. Only SUCCESS is terminal.
        PaymentTransaction declined = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_1"));
        declined.markAsFailed("evt_2");
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_1"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(declined));
        when(mapper.toResponse(declined)).thenReturn(mapped);

        assertThat(service.initiatePayment(request("ref-1", "STRIPE"), "user-1")).isSameAs(mapped);
    }

    @Test
    void aReservedRowThatNeverReachedTheProviderIsRetriedRatherThanStranded() {
        // The window: reserve() commits, the provider call succeeds, and recordInitiation fails. The row is
        // left PENDING with no provider id, so the webhook cannot find it and the client's retry used to get
        // 200 with a null client secret it could do nothing with -- for a reference now permanently taken.
        PaymentTransaction stranded = reservedTransaction();
        PaymentTransaction initiated = initiatedTransaction("pi_123", Map.of("clientSecret", "cs_new"));
        PaymentIntentResponse mapped = new PaymentIntentResponse(Map.of("clientSecret", "cs_new"), "pi_123", "ref-1");
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.of(stranded));
        strategyResolves();
        when(strategy.initiatePayment(any())).thenReturn(
                new PaymentInitiationResult("pi_123", Map.of("clientSecret", "cs_new"))
        );
        when(store.recordInitiation(any(), eq("pi_123"), any())).thenReturn(initiated);
        when(mapper.toResponse(initiated)).thenReturn(mapped);

        PaymentIntentResponse response = service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(response).isSameAs(mapped);
        // No second row: the reference is Stripe's idempotency key, so replaying is safe and reserving again
        // would only violate the constraint that makes the reference unique.
        verify(store, never()).reserve(any(), any(), any());
        verify(store).recordInitiation(any(), eq("pi_123"), any());
    }

    @Test
    void aProviderRefusalAfterTheReservationKeepsTheRowAndRecordsNothing() {
        // Guards deleting or initiating the reserved row on a refusal. The row keeps the key bound to the refused
        // terms, so a same-key retry gets the same refusal and a corrected amount under it gets a 409, which is
        // what the refusal's detail tells the caller.
        PaymentTransaction reserved = reservedTransaction();
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenReturn(reserved);
        when(strategy.initiatePayment(any())).thenThrow(
                new PaymentRefusedException("Amount must convert to at least 50 cents.", new RuntimeException())
        );

        assertThatThrownBy(() -> service.initiatePayment(request("ref-1", "STRIPE"), "user-1"))
                .isInstanceOf(PaymentRefusedException.class);

        verify(store, never()).recordInitiation(any(), any(), any());
    }

    @Test
    void theProviderIsSentExactlyTheContextThatWasValidated() {
        // Guards two contexts drifting apart. The context was once built by hand for validation and again by a
        // mapper for initiation, so a field the mapper dropped would reach Stripe unvetted or empty.
        PaymentTransaction reserved = reservedTransaction();
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenReturn(reserved);
        when(strategy.initiatePayment(any())).thenReturn(
                new PaymentInitiationResult("pi_123", Map.of("clientSecret", "cs_new"))
        );
        when(store.recordInitiation(any(), eq("pi_123"), any())).thenReturn(reserved);

        service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        ArgumentCaptor<PaymentRequestContext> validated = ArgumentCaptor.forClass(PaymentRequestContext.class);
        ArgumentCaptor<PaymentRequestContext> sent = ArgumentCaptor.forClass(PaymentRequestContext.class);
        verify(strategy).validateRequest(validated.capture());
        verify(strategy).initiatePayment(sent.capture());
        assertThat(sent.getValue()).isSameAs(validated.getValue());
        assertThat(sent.getValue()).isEqualTo(
                new PaymentRequestContext("ref-1", new BigDecimal("50.00"), "USD", "user-1")
        );
    }

    @Test
    void theInitiationLogNamesTheReferenceAndNeverTheUserId() {
        // The user id is a bearer credential (ADR 0003); logging it would let anyone who can read application
        // logs act as that user. The reference is already client-supplied and identifies the attempt just as
        // well. See docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md.
        PaymentTransaction reserved = reservedTransaction();
        when(store.findOwnedBy("ref-1", "user-1")).thenReturn(Optional.empty());
        strategyResolves();
        when(store.reserve(any(), eq("user-1"), eq(PaymentProvider.STRIPE))).thenReturn(reserved);
        when(strategy.initiatePayment(any())).thenReturn(
                new PaymentInitiationResult("pi_123", Map.of("clientSecret", "cs_new"))
        );
        when(store.recordInitiation(any(), eq("pi_123"), any())).thenReturn(reserved);

        service.initiatePayment(request("ref-1", "STRIPE"), "user-1");

        assertThat(logs.list)
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("user-1"))
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("ref-1"));
    }

    private void strategyResolves() {
        when(factory.resolve("STRIPE")).thenReturn(PaymentProvider.STRIPE);
        when(factory.getStrategy(PaymentProvider.STRIPE)).thenReturn(strategy);
    }

    private CreatePaymentIntentRequest request(String reference, String provider) {
        return new CreatePaymentIntentRequest(
                reference,
                new BigDecimal("50.00"),
                "USD",
                provider
        );
    }

    private PaymentTransaction reservedTransaction() {
        return PaymentTransaction.create(request("ref-1", "STRIPE"), "user-1", PaymentProvider.STRIPE);
    }

    private PaymentTransaction initiatedTransaction(String providerTransactionId, Map<String, Object> metadata) {
        PaymentTransaction tx = reservedTransaction();
        tx.markAsInitiated(providerTransactionId, metadata);
        return tx;
    }
}
