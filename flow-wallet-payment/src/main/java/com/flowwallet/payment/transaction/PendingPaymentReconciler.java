package com.flowwallet.payment.transaction;

import com.flowwallet.payment.config.PaymentReconciliationProperties;
import com.flowwallet.payment.provider.PaymentProviderFactory;
import com.flowwallet.payment.provider.dto.WebhookResult;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Asks the provider about deposits whose webhook may have been lost, and applies each answer through
 * {@link PaymentTransactionHandler}, the same code and the same checks as a webhook. Without it a payment the
 * customer completed stays PENDING if Stripe's webhook never arrives, and the wallet is never credited.
 * <p>
 * Each row is claimed with a conditional update before the provider call, so instances that select the same row
 * ask about it once, and no lock or transaction is held while the call runs. The reconciler never cancels an
 * intent: a retry under the same key would hand the client a canceled intent it cannot pay.
 * See docs/adr/0032-pending-payments-are-rechecked-with-the-provider.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class PendingPaymentReconciler {
    /**
     * The reason a PaymentFailedEvent carries for a payment the provider canceled. Consumers do not branch on it.
     */
    static final String CANCELED_REASON = "Payment canceled at the provider";

    /**
     * Failed lookups in a row after which a run stops: the provider is down, refuses the key or rate-limits, and the
     * rest of the batch would fail the same way.
     */
    static final int FAILURES_BEFORE_STOPPING = 3;

    private final PaymentTransactionRepository transactions;
    private final PaymentProviderFactory providers;
    private final PaymentTransactionHandler handler;
    private final PaymentReconciliationProperties properties;
    private final MeterRegistry meters;

    enum Outcome {
        COMPLETED,
        FAILED,
        UNCHANGED,
        STILL_OPEN,
        ERROR
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval-ms:300000}")
    public void reconcile() {
        Instant now = Instant.now();
        Instant settledBefore = now.minus(properties.getMinAge());
        List<PaymentTransaction> due = transactions.findReconcilable(
                settledBefore,
                now.minus(properties.getMaxAge()),
                settledBefore,
                WebhookResult.RECONCILED_EVENT_PREFIX + "%",
                PageRequest.of(0, properties.getBatchSize())
        );

        int failuresInARow = 0;
        for (PaymentTransaction transaction : due) {
            // An interrupt means shutdown. Starting another provider call now would only delay it.
            if (Thread.currentThread().isInterrupted()) {
                log.info("Reconciler interrupted; leaving the remaining payments for the next run");
                return;
            }
            if (transactions.claimForReconciliation(transaction.getId(), now, settledBefore) == 0) {
                continue;
            }
            Outcome outcome = reconcile(transaction);
            count(outcome);
            failuresInARow = outcome == Outcome.ERROR ? failuresInARow + 1 : 0;
            if (failuresInARow == FAILURES_BEFORE_STOPPING) {
                log.warn("Reconciler stopped this run after {} failed lookups in a row", failuresInARow);
                return;
            }
        }
    }

    private Outcome reconcile(PaymentTransaction transaction) {
        try {
            WebhookResult result = providers.getStrategy(transaction.getProviderName())
                    .checkPayment(transaction.getProviderTransactionId());
            Outcome outcome = switch (result.eventType()) {
                case PAYMENT_SUCCESS -> handler.handleSuccess(result) ? Outcome.COMPLETED : Outcome.UNCHANGED;
                case PAYMENT_FAILURE ->
                        handler.handleFailure(result, CANCELED_REASON) ? Outcome.FAILED : Outcome.UNCHANGED;
                case UNKNOWN -> Outcome.STILL_OPEN;
            };
            if (outcome == Outcome.COMPLETED) {
                // Each one is a payment whose webhook never arrived, so a steady stream points at the webhook setup.
                log.warn(
                        "Reconciler completed transaction {} without its webhook",
                        transaction.getTransactionReference()
                );
            }
            return outcome;
        } catch (RuntimeException e) {
            log.warn(
                    "Reconciler could not check transaction {}: {}",
                    transaction.getTransactionReference(),
                    e.toString()
            );
            return Outcome.ERROR;
        }
    }

    private void count(Outcome outcome) {
        meters.counter("payment.reconciliation.outcomes", "outcome", outcome.name().toLowerCase(Locale.ROOT))
                .increment();
    }
}
