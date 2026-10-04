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
import java.util.function.Predicate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionHandler {
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentOutboxService outboxService;

    /**
     * Applies a verified success, from a webhook or from the reconciler. A success whose amount or currency differs
     * from the stored transaction leaves the row unchanged and is logged at ERROR; the webhook is still
     * acknowledged, since a redelivery of the same event could change nothing.
     * See docs/adr/0017-webhooks-verified-before-they-are-read.md.
     *
     * @return {@code true} only if the payment moved to SUCCESS and a PaymentCompletedEvent was written
     */
    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttemptsExpression = "${payment.retry.optimistic-lock.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${payment.retry.optimistic-lock.backoff-delay-ms:50}")
    )
    public boolean handleSuccess(WebhookResult result) {
        log.info("Processing payment success for provider tx: {}", result.providerTransactionId());

        return applyToUnprocessedTransaction(
                result, tx -> {
                    Optional<String> differences = tx.differencesFromConfirmed(result.amount(), result.currency());
                    if (differences.isPresent()) {
                        log.error(
                                "Ignoring success event {} for tx {}: the provider reports a different {}",
                                result.providerEventId(),
                                tx.getTransactionReference(),
                                differences.get()
                        );
                        return false;
                    }
                    if (!tx.markAsSuccess(result.providerEventId())) {
                        return false;
                    }
                    transactionRepository.save(tx);
                    outboxService.publishPaymentCompleted(tx, result.occurredAt());
                    log.info("Successfully processed payment success for tx: {}", tx.getTransactionReference());
                    return true;
                }
        );
    }

    /**
     * Applies a failure, from a webhook or from the reconciler. Only a PENDING payment can fail.
     *
     * @param reason a fixed description written by this service, carried into the PaymentFailedEvent; never the
     *               provider's decline message (docs/adr/0009-payment-event-contract.md)
     * @return {@code true} only if the payment moved to FAILED and a PaymentFailedEvent was written
     */
    @Transactional
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttemptsExpression = "${payment.retry.optimistic-lock.max-attempts:3}",
            backoff = @Backoff(delayExpression = "${payment.retry.optimistic-lock.backoff-delay-ms:50}")
    )
    public boolean handleFailure(WebhookResult result, String reason) {
        log.info("Processing payment failure for provider tx: {}", result.providerTransactionId());

        return applyToUnprocessedTransaction(
                result, tx -> {
                    if (!tx.markAsFailed(result.providerEventId())) {
                        log.info(
                                "Ignoring payment failure for {} transaction: {}",
                                tx.getStatus(),
                                tx.getTransactionReference()
                        );
                        return false;
                    }
                    transactionRepository.save(tx);
                    outboxService.publishPaymentFailed(tx, reason, result.occurredAt());
                    log.info("Successfully processed payment failure for tx: {}", tx.getTransactionReference());
                    return true;
                }
        );
    }

    private boolean applyToUnprocessedTransaction(WebhookResult result, Predicate<PaymentTransaction> action) {
        if (transactionRepository.existsByProviderEventId(result.providerEventId())) {
            log.info("Event {} already processed. Ignoring.", result.providerEventId());
            return false;
        }

        // An intent this service never created (stripe trigger, the dashboard) is logged and acknowledged, since a
        // retry could change nothing. See docs/adr/0016-error-model-and-status-codes.md.
        return transactionRepository.findByProviderTransactionId(result.providerTransactionId())
                .map(action::test)
                .orElseGet(() -> {
                    log.warn(
                            "Ignoring event {} for provider tx {}: no transaction in this service",
                            result.providerEventId(),
                            result.providerTransactionId()
                    );
                    return false;
                });
    }
}
