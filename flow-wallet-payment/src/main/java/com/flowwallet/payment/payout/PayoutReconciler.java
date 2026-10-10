package com.flowwallet.payment.payout;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Asks Stripe about payouts whose webhook may be lost, and settles each through {@link PayoutSettler}, the code the
 * Connect webhook uses: created payouts from a minimum age on, and paid ones for a period after they were paid, since
 * Stripe can fail a paid payout days later. Without a Connect secret it alone settles payouts.
 * <p>
 * Each row is claimed with a conditional update of {@code last_reconciled_at} before Stripe is called, so instances
 * that select the same row ask about it once, and nothing is held open while Stripe answers. It never touches the
 * executor's {@code ACCEPTED}, {@code TRANSFERRED} and {@code REVERSING} rows. A run reads at most one batch, created
 * payouts first, and stops at its budget, on an interrupt or after three failed reads in a row.
 * See docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.payout.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class PayoutReconciler {
    /**
     * Failed reads in a row after which a run stops: Stripe is down, refuses the key or rate-limits, and the rest of
     * the batch would fail the same way.
     */
    static final int FAILURES_BEFORE_STOPPING = 3;

    private final PayoutStore store;
    private final PayoutSettler settler;
    private final PayoutProperties.Reconciler settings;
    private final MeterRegistry meters;

    PayoutReconciler(PayoutStore store, PayoutSettler settler, PayoutProperties properties, MeterRegistry meters) {
        this.store = store;
        this.settler = settler;
        this.settings = properties.getReconciler();
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${payment.payout.reconciler.interval-ms:60000}")
    public void reconcile() {
        Instant started = Instant.now();
        Instant deadline = started.plus(settings.getRunBudget());
        Instant createdCutoff = started.minus(settings.getCreatedMinAge());
        Instant paidCutoff = started.minus(settings.getPaidRecheck());

        List<ReconcilablePayout> due = new ArrayList<>(
                store.findCreatedToReconcile(createdCutoff, settings.getBatchSize())
        );
        int room = settings.getBatchSize() - due.size();
        if (room > 0) {
            due.addAll(store.findPaidToReconcile(started.minus(settings.getPaidPeriod()), paidCutoff, room));
        }

        int failuresInARow = 0;
        for (ReconcilablePayout row : due) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Payout reconciler interrupted; leaving the remaining payouts for the next run");
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                log.info("Payout reconciler spent its run budget; leaving the remaining payouts for the next run");
                return;
            }
            Instant cutoff = row.status() == PayoutStatus.PAID ? paidCutoff : createdCutoff;
            if (!store.claimForReconciliation(row, Instant.now(), cutoff)) {
                continue;
            }
            try {
                Settlement settlement = settler.settle(row.stripeAccountId(), row.stripePayoutId());
                if (settlement.kind() == Settlement.Kind.MOVED) {
                    // Each one is a payout whose webhook never arrived, so a steady stream points at the webhook setup.
                    log.warn(
                            "Payout {} settled ({} to {}) without its webhook",
                            row.reference(),
                            settlement.from(),
                            settlement.to()
                    );
                }
                count(row.status(), settlement.kind().name().toLowerCase(Locale.ROOT));
                failuresInARow = 0;
            } catch (RuntimeException e) {
                log.warn("Payout reconciler could not check payout {}: {}", row.reference(), e.toString());
                count(row.status(), "error");
                failuresInARow++;
                if (failuresInARow == FAILURES_BEFORE_STOPPING) {
                    log.warn("Payout reconciler stopped this run after {} failed reads in a row", failuresInARow);
                    return;
                }
            }
        }
    }

    private void count(PayoutStatus status, String outcome) {
        meters.counter(
                "payment.payouts.reconciler.outcomes",
                "status",
                status.name().toLowerCase(Locale.ROOT),
                "outcome",
                outcome
        ).increment();
    }
}
