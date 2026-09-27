package com.flowwallet.payment.transaction;

import com.flowwallet.payment.outbox.PaymentOutboxService;
import com.flowwallet.payment.provider.dto.WebhookResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionHandler {
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentOutboxService outboxService;

    /**
     * Applies a verified success event. An event whose amount or currency differs from the stored transaction
     * leaves the row unchanged and is logged at ERROR; the webhook is still acknowledged, since a redelivery of
     * the same event could change nothing. See docs/adr/0017-webhooks-verified-before-they-are-read.md.
     */
    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttemptsExpression = "${payment.retry.optimistic-lock.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${payment.retry.optimistic-lock.backoff-delay-ms:50}")
    )
    public void handleSuccess(WebhookResult result) {
        log.info("Processing payment success for provider tx: {}", result.providerTransactionId());

        processUnprocessedTransaction(
                result, tx -> {
                    Optional<String> differences = tx.differencesFromConfirmed(result.amount(), result.currency());
                    if (differences.isPresent()) {
                        log.error(
                                "Ignoring success event {} for tx {}: the provider reports a different {}",
                                result.providerEventId(),
                                tx.getTransactionReference(),
                                differences.get()
                        );
                        return;
                    }
                    if (tx.markAsSuccess(result.providerEventId())) {
                        transactionRepository.save(tx);
                        outboxService.publishPaymentCompleted(tx);
                        log.info("Successfully processed payment success for tx: {}", tx.getTransactionReference());
                    }
                }
        );
    }

    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttemptsExpression = "${payment.retry.optimistic-lock.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${payment.retry.optimistic-lock.backoff-delay-ms:50}")
    )
    public void handleFailure(WebhookResult result) {
        log.info("Processing payment failure for provider tx: {}", result.providerTransactionId());

        processUnprocessedTransaction(
                result, tx -> {
                    if (tx.markAsFailed(result.providerEventId())) {
                        transactionRepository.save(tx);
                        outboxService.publishPaymentFailed(tx, "Payment failed via webhook");
                        log.info("Successfully processed payment failure for tx: {}", tx.getTransactionReference());
                    } else {
                        log.info(
                                "Ignoring payment failure for {} transaction: {}",
                                tx.getStatus(),
                                tx.getTransactionReference()
                        );
                    }
                }
        );
    }

    private void processUnprocessedTransaction(WebhookResult result, Consumer<PaymentTransaction> action) {
        if (transactionRepository.existsByProviderEventId(result.providerEventId())) {
            log.info("Event {} already processed. Ignoring.", result.providerEventId());
            return;
        }

        // An intent this service never created (stripe trigger, the dashboard) is logged and acknowledged, since a
        // retry could change nothing. See docs/adr/0016-error-model-and-status-codes.md.
        transactionRepository.findByProviderTransactionId(result.providerTransactionId()).ifPresentOrElse(
                action,
                () -> log.warn(
                        "Ignoring event {} for provider tx {}: no transaction in this service",
                        result.providerEventId(),
                        result.providerTransactionId()
                )
        );
    }
}
