package com.flowwallet.payment.outbox;

import com.flowwallet.contract.constant.KafkaConstants;
import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.transaction.PaymentTransaction;
import com.flowwallet.payment.transaction.mapper.PaymentEventMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOutboxServiceTest {
    private static final Instant SETTLED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final long SAVED_ID = 42L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxEventRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private PaymentOutboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(repository.save(any(OutboxEvent.class))).thenReturn(OutboxEvent.builder().id(SAVED_ID).build());
        service = new PaymentOutboxService(repository, eventPublisher, new PaymentEventMapperImpl(), objectMapper);
    }

    @Test
    void aCompletedPaymentIsQueuedUnderTheCompletedEventTypeWithACompletedPayload() {
        // The wallet credits on the eventType header alone, so the header and the payload must name the same event.
        service.publishPaymentCompleted(transaction(), SETTLED_AT);

        OutboxEvent saved = savedRow();
        assertThat(saved.getEventType()).isEqualTo(KafkaConstants.EVENT_TYPE_PAYMENT_COMPLETED);
        assertThat(saved.getAggregateType()).isEqualTo(PaymentOutboxService.AGGREGATE_TYPE_PAYMENT_TRANSACTION);
        assertThat(saved.getAggregateId()).isEqualTo("ref-1");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertThat(payload.has("completedAt")).isTrue();
        assertThat(payload.has("failedAt")).isFalse();
        assertThat(payload.get("transactionReference").asString()).isEqualTo("ref-1");
    }

    @Test
    void aFailedPaymentIsQueuedUnderTheFailedEventTypeWithAFailedPayload() {
        // A completed header on this row would make the wallet credit a payment that failed.
        service.publishPaymentFailed(transaction(), "card declined", SETTLED_AT);

        OutboxEvent saved = savedRow();
        assertThat(saved.getEventType()).isEqualTo(KafkaConstants.EVENT_TYPE_PAYMENT_FAILED);
        assertThat(saved.getAggregateType()).isEqualTo(PaymentOutboxService.AGGREGATE_TYPE_PAYMENT_TRANSACTION);
        assertThat(saved.getAggregateId()).isEqualTo("ref-1");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertThat(payload.has("failedAt")).isTrue();
        assertThat(payload.has("completedAt")).isFalse();
        assertThat(payload.get("reason").asString()).isEqualTo("card declined");
    }

    @Test
    void theFastPathIsToldTheIdOfTheSavedRowForBothEvents() {
        // The listener claims the row by this id after commit; a wrong or null id leaves every event to the poller.
        service.publishPaymentCompleted(transaction(), SETTLED_AT);
        service.publishPaymentFailed(transaction(), "card declined", SETTLED_AT);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues()).containsOnly(new OutboxCreatedEvent(SAVED_ID));
    }

    @Test
    void theServiceRefusesToRunOutsideTheStatusChangeTransaction() {
        // Without MANDATORY a call from outside the handler's transaction would commit the row on its own: the
        // dual write the outbox exists to prevent.
        Transactional transactional = AnnotationUtils.findAnnotation(PaymentOutboxService.class, Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private OutboxEvent savedRow() {
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    private PaymentTransaction transaction() {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(
                "ref-1",
                new BigDecimal("50.00"),
                "USD",
                "STRIPE"
        );
        return PaymentTransaction.create(request, "user-1");
    }
}
