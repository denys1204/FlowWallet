package com.flowwallet.payment.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.flowwallet.common.enums.TransactionStatus;
import com.flowwallet.common.event.PaymentCompletedEvent;
import com.flowwallet.payment.entity.OutboxEvent;
import com.flowwallet.payment.entity.PaymentTransaction;
import com.flowwallet.payment.exception.TransactionNotFoundException;
import com.flowwallet.payment.event.OutboxCreatedEvent;
import com.flowwallet.payment.repository.OutboxEventRepository;
import com.flowwallet.payment.repository.PaymentTransactionRepository;
import com.flowwallet.payment.service.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionHandler {
    private final PaymentTransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public void handleSuccess(String providerTransactionId, String providerEventId) {
        log.info("Processing payment success for provider tx: {}", providerTransactionId);

        if (isAlreadyProcessed(providerEventId)) {
            return;
        }

        PaymentTransaction tx = findTransaction(providerTransactionId);
        if (tx.getStatus() == TransactionStatus.SUCCESS) {
            return;
        }

        tx.markAsSuccess(providerEventId);
        transactionRepository.save(tx);

        saveOutboxEvent(tx);
        log.info("Successfully processed payment success for tx: {}", tx.getTransactionReference());
    }

    @Transactional
    public void handleFailure(String providerTransactionId, String providerEventId) {
        log.info("Processing payment failure for provider tx: {}", providerTransactionId);

        if (isAlreadyProcessed(providerEventId)) {
            return;
        }

        PaymentTransaction tx = findTransaction(providerTransactionId);
        tx.markAsFailed(providerEventId);
        transactionRepository.save(tx);

        log.info("Successfully processed payment failure for tx: {}", tx.getTransactionReference());
    }

    private boolean isAlreadyProcessed(String providerEventId) {
        if (transactionRepository.existsByProviderEventId(providerEventId)) {
            log.info("Event {} already processed. Ignoring.", providerEventId);
            return true;
        }
        return false;
    }

    private PaymentTransaction findTransaction(String providerTransactionId) {
        return transactionRepository.findByProviderTransactionId(providerTransactionId).orElseThrow(
                () -> new TransactionNotFoundException("Transaction not found for provider tx: " + providerTransactionId)
        );
    }

    private void saveOutboxEvent(PaymentTransaction tx) {
        try {
            PaymentCompletedEvent event = eventMapper.toPaymentCompletedEvent(tx);
            OutboxEvent outboxEvent = eventMapper.toOutboxEvent(tx, objectMapper.writeValueAsString(event));
            outboxEventRepository.save(outboxEvent);
            eventPublisher.publishEvent(new OutboxCreatedEvent(outboxEvent.getId()));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize PaymentCompletedEvent for tx: " + tx.getTransactionReference(), e);
        }
    }
}
