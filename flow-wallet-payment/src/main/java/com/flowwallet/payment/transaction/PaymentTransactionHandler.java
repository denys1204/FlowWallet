package com.flowwallet.payment.transaction;

import com.flowwallet.common.enums.TransactionStatus;
import com.flowwallet.payment.outbox.PaymentOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionHandler {
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentOutboxService outboxService;

    @Transactional
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class)
    public void handleSuccess(String providerTransactionId, String providerEventId) {
        log.info("Processing payment success for provider tx: {}", providerTransactionId);

        processUnprocessedTransaction(providerTransactionId, providerEventId, tx -> {
            if (tx.getStatus() == TransactionStatus.SUCCESS) {
                return;
            }

            tx.markAsSuccess(providerEventId);
            transactionRepository.save(tx);
            outboxService.publishPaymentCompleted(tx);

            log.info("Successfully processed payment success for tx: {}", tx.getTransactionReference());
        });
    }

    @Transactional
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class)
    public void handleFailure(String providerTransactionId, String providerEventId) {
        log.info("Processing payment failure for provider tx: {}", providerTransactionId);

        processUnprocessedTransaction(providerTransactionId, providerEventId, tx -> {
            tx.markAsFailed(providerEventId);
            transactionRepository.save(tx);
            outboxService.publishPaymentFailed(tx, "Payment failed via webhook");

            log.info("Successfully processed payment failure for tx: {}", tx.getTransactionReference());
        });
    }

    private void processUnprocessedTransaction(String providerTransactionId, String providerEventId, Consumer<PaymentTransaction> action) {
        if (transactionRepository.existsByProviderEventId(providerEventId)) {
            log.info("Event {} already processed. Ignoring.", providerEventId);
            return;
        }

        PaymentTransaction tx = transactionRepository.findByProviderTransactionId(providerTransactionId).orElseThrow(
                () -> new TransactionNotFoundException("Transaction not found for provider tx: " + providerTransactionId)
        );

        action.accept(tx);
    }
}
