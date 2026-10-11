package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Works due payouts through their Transfer, Payout and Reversal steps. Each row is claimed with a conditional update
 * before any Stripe call, the step's calls run with no transaction open, and the outcome is recorded only while the
 * row still holds the claim's token, so two instances never act on one step at once and an outcome another writer
 * overtook changes nothing. The executor sees no user id, so its log lines name the reference and Stripe's ids.
 * <p>
 * A run claims at most a batch, stops once its time budget is spent, after three failures in a row, or on an
 * interrupt; an outcome that settled nothing, seen while interrupted, gives the claim up without counting a try and
 * waits the unknown backoff, because the cut-short call may still be running at Stripe.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.payout.executor.enabled", havingValue = "true", matchIfMissing = true)
public class PayoutExecutor {
    /**
     * Failures in a row after which a run stops: Stripe is down, refuses the key or rate-limits, and the rest of the
     * batch would fail the same way.
     */
    static final int FAILURES_BEFORE_STOPPING = 3;

    private final PayoutStore store;
    private final PayoutSteps steps;
    private final PayoutProperties.Executor settings;
    private final MeterRegistry meters;

    /**
     * Fails startup unless the lease covers a step's three Stripe calls, each at its whole budget under the Stripe
     * timeouts and retries, and two margins: a claim must never lapse while one of its calls is still running.
     */
    PayoutExecutor(
            PayoutStore store,
            PayoutSteps steps,
            PayoutProperties properties,
            StripeProperties stripe,
            MeterRegistry meters
    ) {
        this.store = store;
        this.steps = steps;
        this.settings = properties.getExecutor();
        this.meters = meters;

        Duration required = stripe.getApi()
                .callBudget()
                .multipliedBy(PayoutSteps.MAX_CALLS_PER_STEP)
                .plus(PayoutSteps.LEASE_MARGIN.multipliedBy(2));
        if (settings.getLease().compareTo(required) < 0) {
            throw new IllegalStateException(
                    "payment.payout.executor.lease (" + settings.getLease() + ") must be at least " + required
                            + ": three Stripe calls under stripe.api.connect-timeout, read-timeout and"
                            + " max-network-retries, and two margins of " + PayoutSteps.LEASE_MARGIN
            );
        }
    }

    @Scheduled(fixedDelayString = "${payment.payout.executor.interval-ms:10000}")
    public void run() {
        Instant started = Instant.now();
        Instant deadline = started.plus(settings.getRunBudget());
        List<DuePayout> due = store.findDue(started, settings.getBatchSize());

        int failuresInARow = 0;
        for (DuePayout row : due) {
            // An interrupt means shutdown. Starting another Stripe call now would only delay it.
            if (Thread.currentThread().isInterrupted()) {
                log.info("Payout executor interrupted; leaving the remaining payouts for the next run");
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                log.info("Payout executor spent its run budget; leaving the remaining payouts for the next run");
                return;
            }
            boolean failed;
            try {
                failed = work(row);
            } catch (RuntimeException e) {
                log.warn("Payout executor could not work payout {} in {}", row.id(), row.status(), e);
                count(row.status(), "error");
                failed = true;
            }
            failuresInARow = failed ? failuresInARow + 1 : 0;
            if (failuresInARow == FAILURES_BEFORE_STOPPING) {
                log.warn("Payout executor stopped this run after {} failures in a row", failuresInARow);
                return;
            }
        }
    }

    /**
     * @return whether the step's outcome counts as a failure
     */
    private boolean work(DuePayout row) {
        // Both ends at the column's precision, so the token read back equals the one written.
        Instant claimedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant claimedUntil = claimedAt.plus(settings.getLease()).truncatedTo(ChronoUnit.MICROS);
        Optional<ClaimedPayout> claimed = store.claim(row, claimedAt, claimedUntil);
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedPayout claim = claimed.get();

        StepOutcome outcome = steps.work(claim);
        if (outcome.isFailure() && Thread.currentThread().isInterrupted()) {
            outcome = new StepOutcome.Postponed(Instant.now().plus(settings.getUnknownBackoff()), "interrupted");
        }
        RecordResult result = store.record(claim, outcome, Instant.now());
        report(claim, outcome, result);
        return outcome.isFailure();
    }

    private void report(ClaimedPayout claim, StepOutcome outcome, RecordResult result) {
        String reference = claim.reference();
        switch (result) {
            case TAKEN_BY_CLAIM -> {
                log.warn("Payout {} lost its claim to another executor; {} was not recorded", reference, outcome);
                count(claim.step(), "claim_lost");
                return;
            }
            case TAKEN_BY_TRANSITION -> {
                log.info(
                        "Payout {} was moved by another writer before its {} outcome was recorded",
                        reference,
                        outcome.label()
                );
                count(claim.step(), "claim_lost");
                return;
            }
            case RECORDED -> count(claim.step(), outcome.label());
        }
        switch (outcome) {
            case StepOutcome.Transferred transferred -> {
                if (transferred.adopted()) {
                    String id = transferred.stripeTransferId();
                    log.warn("Payout {} adopted transfer {} found by lookup", reference, id);
                }
                log.info("Payout {} transferred as {}", reference, transferred.stripeTransferId());
            }
            case StepOutcome.Returned returned -> log.info(
                    "Payout {} returned before its transfer: {} ({})",
                    reference,
                    returned.reason(),
                    returned.providerCode()
            );
            case StepOutcome.PayoutCreated created -> {
                if (created.adopted()) {
                    log.warn("Payout {} adopted payout {} found by lookup", reference, created.stripePayoutId());
                }
                log.info("Payout {} paid out as {}", reference, created.stripePayoutId());
            }
            case StepOutcome.FailedPayoutAdopted failed -> log.warn(
                    "Payout {} reversing: {} ({})",
                    reference,
                    PayoutReason.PAYOUT_FAILED,
                    failed.failureCode()
            );
            case StepOutcome.ReversalStarted reversal ->
                    log.warn("Payout {} reversing: {} ({})", reference, reversal.reason(), reversal.failureCode());
            case StepOutcome.BankAccountRejected rejected -> log.warn(
                    "Payout {} reversing: {} ({})",
                    reference,
                    PayoutReason.BANK_ACCOUNT_REJECTED,
                    rejected.providerCode()
            );
            case StepOutcome.Paid _ -> log.info("Payout {} paid", reference);
            case StepOutcome.Reversed reversed ->
                    log.info("Payout {} reversed as {}", reference, reversed.stripeReversalId());
            case StepOutcome.RetryUnknown retry -> log.warn(
                    "Payout {} {} outcome unknown, attempt {}, next at {}: {}",
                    reference,
                    stepName(claim.step()),
                    claim.attempts() + 1,
                    retry.nextAttemptAt(),
                    retry.cause()
            );
            case StepOutcome.RetryRefused retry -> log.warn(
                    "Payout {} {} refused ({}), retrying under key generation {} at {}",
                    reference,
                    stepName(claim.step()),
                    retry.providerCode(),
                    claim.keyGen() + 1,
                    retry.nextAttemptAt()
            );
            case StepOutcome.KeySpent spent -> log.warn(
                    "Payout {} transfer key generation {} answers with transfer {}, reversed in full; the next try "
                            + "uses generation {}",
                    reference,
                    claim.keyGen(),
                    spent.stripeTransferId(),
                    claim.keyGen() + 1
            );
            case StepOutcome.Attention attention -> {
                log.error(
                        "Payout {} needs attention from {} ({}): {}",
                        reference,
                        claim.step(),
                        attention.cause(),
                        attention.detail()
                );
                meters.counter(
                        "payment.payouts.needs.attention",
                        "from",
                        claim.step().name().toLowerCase(Locale.ROOT),
                        "cause",
                        attention.cause().name().toLowerCase(Locale.ROOT)
                ).increment();
            }
            case StepOutcome.Released released ->
                    log.info("Payout {} gave its claim up: {}", reference, released.why());
            case StepOutcome.Postponed postponed -> log.info(
                    "Payout {} gave its claim up ({}); next try at {}",
                    reference,
                    postponed.why(),
                    postponed.nextAttemptAt()
            );
        }
    }

    private void count(PayoutStatus step, String outcome) {
        meters.counter("payment.payouts.executor.outcomes", "step", stepName(step), "outcome", outcome).increment();
    }

    private static String stepName(PayoutStatus step) {
        return switch (step) {
            case ACCEPTED -> "transfer";
            case TRANSFERRED -> "payout";
            case REVERSING -> "reversal";
            default -> step.name().toLowerCase(Locale.ROOT);
        };
    }
}
