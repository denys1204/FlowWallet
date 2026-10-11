package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The short transactions around the Stripe calls of payouts, so no connection is held while Stripe answers. For the
 * executor: the claim before its calls and the record after them; the record locks the row and applies the outcome
 * only while the row still holds the claim's token, and every transition clears the token, so an outcome another
 * writer overtook changes nothing and writes no second event. For the Connect webhook and the payout reconciler: the
 * settlement of a payout from its status at Stripe, which locks the row by its payout id or reference and goes
 * through the same guarded transitions without a token, the reconciler's selections and its claim, and the reset of
 * the stored bank account after a failed payout. For the {@code payouts} actuator endpoint: the operator's snapshot,
 * a requeue under the row's lock, and a return or reversal that writes only while the row's {@code version} is the
 * one read before Stripe was asked. Rows are locked in the order payouts, then payout accounts.
 * See docs/adr/0006-short-transactions-across-bean-boundaries.md,
 * docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md,
 * docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md and
 * docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
@Component
@RequiredArgsConstructor
public class PayoutStore {
    private final PayoutRepository payouts;
    private final PayoutAccountRepository accounts;
    private final PayoutOutboxService outbox;

    @Transactional(readOnly = true)
    public List<DuePayout> findDue(Instant now, int limit) {
        return payouts.findDue(now, PageRequest.of(0, limit));
    }

    /**
     * Claims the row for its current step and reads what the step needs, in the claim's transaction, which holds the
     * row's lock from the update on.
     *
     * @return empty when the row moved, is not due or another executor holds it
     */
    @Transactional
    public Optional<ClaimedPayout> claim(DuePayout due, Instant claimedAt, Instant claimedUntil) {
        int claimed = switch (due.status()) {
            case ACCEPTED -> payouts.claimTransfer(due.id(), claimedAt, claimedUntil);
            case TRANSFERRED -> payouts.claimPayout(due.id(), claimedAt, claimedUntil);
            case REVERSING -> payouts.claimReversal(due.id(), claimedAt, claimedUntil);
            default -> 0;
        };
        if (claimed == 0) {
            return Optional.empty();
        }
        Payout payout = payouts.findById(due.id())
                .orElseThrow(() -> new IllegalStateException("Claimed payout " + due.id() + " is gone"));
        return Optional.of(ClaimedPayout.of(payout, claimedAt, accounts.findByUserId(payout.getUserId())));
    }

    /**
     * Applies the outcome of a claimed step, with its outbox event, if the row still holds the claim's token.
     */
    @Transactional
    public RecordResult record(ClaimedPayout claim, StepOutcome outcome, Instant now) {
        Payout payout = payouts.lockById(claim.id())
                .orElseThrow(() -> new IllegalStateException("Claimed payout " + claim.reference() + " is gone"));
        if (!claim.claimedUntil().equals(payout.getClaimedUntil())) {
            return payout.getStatus() == claim.step() ? RecordResult.TAKEN_BY_CLAIM : RecordResult.TAKEN_BY_TRANSITION;
        }
        apply(payout, outcome, now);
        return RecordResult.RECORDED;
    }

    /**
     * Applies a payout's current status at Stripe to its row, for the Connect webhook and the payout reconciler. It
     * locks the row by the payout's id, or by the reference in its metadata for a payout the executor has not recorded
     * yet, and skips the token check: a status Stripe reports overrides any claim, and the transition clears it. The
     * status, never an event's type, decides; every transition is guarded, so a redelivery or a race with another
     * writer moves nothing a second time. A failed payout sets the stored bank account back to {@code NOT_SET} when it
     * was sent to it, whatever the row's status; a canceled one does not.
     * See docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md.
     */
    @Transactional
    public Settlement settle(String accountId, ProviderPayout found, Instant now) {
        Optional<Payout> row = payouts.lockByStripePayoutId(found.id());
        if (row.isEmpty() && found.reference() != null) {
            row = payouts.lockByTransactionReference(found.reference());
        }
        if (row.isEmpty()) {
            return new Settlement(
                    Settlement.Kind.UNKNOWN_PAYOUT,
                    found.reference(),
                    found.status(),
                    null,
                    null,
                    false,
                    null
            );
        }
        Payout payout = row.get();
        PayoutStatus from = payout.getStatus();
        String differences = differences(accountId, found, payout);
        if (differences != null) {
            return new Settlement(
                    Settlement.Kind.DIFFERING_TERMS,
                    payout.getTransactionReference(),
                    found.status(),
                    from,
                    from,
                    false,
                    differences
            );
        }

        List<StepOutcome> outcomes = settlementOf(payout, found);
        outcomes.forEach(outcome -> apply(payout, outcome, now));
        boolean bankReset = found.status() == ProviderPayoutStatus.FAILED
                && resetBankAfterFailure(payout, found.destinationId());

        Settlement.Kind kind = !outcomes.isEmpty()
                ? Settlement.Kind.MOVED
                : agrees(from, found.status()) ? Settlement.Kind.REPEATED : Settlement.Kind.CONTRADICTED;
        return new Settlement(
                kind,
                payout.getTransactionReference(),
                found.status(),
                from,
                payout.getStatus(),
                bankReset,
                null
        );
    }

    /**
     * The transitions a payout's status at Stripe makes from the row's locked status. A row still in
     * {@code TRANSFERRED} adopts the payout first, as the executor's lookup does, and the status then applies to
     * {@code PAYOUT_CREATED}. A row in {@code NEEDS_ATTENTION} is an operator's and moves nothing.
     */
    static List<StepOutcome> settlementOf(Payout locked, ProviderPayout found) {
        List<StepOutcome> outcomes = new ArrayList<>();
        PayoutStatus status = locked.getStatus();
        if (status == PayoutStatus.TRANSFERRED && locked.getStripePayoutId() == null) {
            outcomes.add(new StepOutcome.PayoutCreated(found.id(), true));
            status = PayoutStatus.PAYOUT_CREATED;
        }
        boolean failed = found.status() == ProviderPayoutStatus.FAILED
                || found.status() == ProviderPayoutStatus.CANCELED;
        if (status == PayoutStatus.PAYOUT_CREATED && found.status() == ProviderPayoutStatus.PAID) {
            outcomes.add(new StepOutcome.Paid());
        } else if ((status == PayoutStatus.PAYOUT_CREATED || status == PayoutStatus.PAID) && failed) {
            // However long ago a paid payout was paid: Stripe can fail one days later.
            outcomes.add(new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, found.failureCode()));
        }
        return outcomes;
    }

    @Transactional(readOnly = true)
    public List<ReconcilablePayout> findCreatedToReconcile(Instant lookedAtBefore, int limit) {
        return payouts.findCreatedToReconcile(lookedAtBefore, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<ReconcilablePayout> findPaidToReconcile(Instant paidAfter, Instant lookedAtBefore, int limit) {
        return payouts.findPaidToReconcile(paidAfter, lookedAtBefore, PageRequest.of(0, limit));
    }

    /**
     * @return whether this caller may ask Stripe about the row: no other instance looked at it since the cutoff
     */
    @Transactional
    public boolean claimForReconciliation(ReconcilablePayout row, Instant now, Instant lookedAtBefore) {
        return payouts.claimForReconciliation(row.id(), row.status(), now, lookedAtBefore) == 1;
    }

    /**
     * Sets the stored bank account back to {@code NOT_SET} if a failed payout was sent to it. A user who set another
     * bank account since keeps it.
     *
     * @return whether the state changed
     */
    private boolean resetBankAfterFailure(Payout payout, @Nullable String externalAccountId) {
        if (externalAccountId == null || payout.getStripeAccountId() == null) {
            return false;
        }
        return accounts.lockByStripeAccountId(payout.getStripeAccountId())
                .filter(account -> externalAccountId.equals(account.getExternalAccountId()))
                .map(account -> account.updateBankState(BankAccountState.NOT_SET))
                .orElse(false);
    }

    /**
     * What a verified payout and the row disagree on; null when they agree. A second payout for the reference counts
     * as a disagreement.
     */
    private static @Nullable String differences(String accountId, ProviderPayout found, Payout payout) {
        List<String> differences = new ArrayList<>();
        if (!accountId.equals(payout.getStripeAccountId())) {
            differences.add("account");
        }
        if (found.amount().compareTo(payout.getAmount()) != 0) {
            differences.add("amount");
        }
        if (!found.currency().equalsIgnoreCase(payout.getCurrency())) {
            differences.add("currency");
        }
        if (payout.getStripePayoutId() != null && !payout.getStripePayoutId().equals(found.id())) {
            differences.add("payout id");
        }
        if (found.reference() != null && !found.reference().equals(payout.getTransactionReference())) {
            differences.add("reference");
        }
        return differences.isEmpty() ? null : String.join(", ", differences);
    }

    /**
     * Whether the row already shows what Stripe reports, as after a redelivery.
     */
    private static boolean agrees(PayoutStatus row, ProviderPayoutStatus stripe) {
        return switch (row) {
            case PAYOUT_CREATED -> stripe == ProviderPayoutStatus.PENDING || stripe == ProviderPayoutStatus.IN_TRANSIT;
            case PAID -> stripe == ProviderPayoutStatus.PAID;
            case REVERSING, RETURNED ->
                    stripe == ProviderPayoutStatus.FAILED || stripe == ProviderPayoutStatus.CANCELED;
            default -> false;
        };
    }

    @Transactional(readOnly = true)
    public Optional<PayoutSnapshot> findForOperator(String reference) {
        return payouts.findByTransactionReference(reference).map(PayoutSnapshot::of);
    }

    @Transactional(readOnly = true)
    public List<PayoutSnapshot> findAwaitingOperator(int limit) {
        return payouts.findAwaitingOperator(PageRequest.of(0, limit)).stream().map(PayoutSnapshot::of).toList();
    }

    @Transactional(readOnly = true)
    public long countAwaitingOperator(PayoutStatus from) {
        return payouts.countAwaitingOperator(from);
    }

    /**
     * Returns a waiting payout to the step it came from. It needs no Stripe check: the step looks for what Stripe holds
     * before it creates, under the key generation it had, so it writes no event.
     */
    @Transactional
    public OperatorWrite requeue(String reference, Instant now) {
        Optional<Payout> row = payouts.lockByTransactionReference(reference);
        if (row.isEmpty()) {
            return OperatorWrite.notFound();
        }
        Payout payout = row.get();
        if (payout.getStatus() != PayoutStatus.NEEDS_ATTENTION) {
            return new OperatorWrite(OperatorWrite.Kind.NOT_WAITING, PayoutSnapshot.of(payout));
        }
        require(payout, payout.requeue(now));
        return new OperatorWrite(OperatorWrite.Kind.APPLIED, PayoutSnapshot.of(payout));
    }

    /**
     * Returns a waiting payout to the wallet without a reversal, with its {@code PayoutReturnedEvent}, if the row is
     * still the one the caller's Stripe check read.
     */
    @Transactional
    public OperatorWrite operatorReturn(Long id, Long seenVersion, Instant now) {
        return operatorWrite(id, seenVersion, payout -> {
            require(payout, payout.operatorReturn(now));
            outbox.publishReturned(payout, now);
        });
    }

    /**
     * Sends a payout waiting from {@code TRANSFERRED} to its reversal, with its {@code PayoutFailedEvent}, the only one
     * it gets: a row in {@code TRANSFERRED} never sent one. Only while the row is the one the caller's Stripe check
     * read.
     */
    @Transactional
    public OperatorWrite operatorReverse(Long id, Long seenVersion, Instant now) {
        return operatorWrite(id, seenVersion, payout -> {
            require(payout, payout.operatorReverse(now));
            outbox.publishFailed(payout, now);
        });
    }

    private OperatorWrite operatorWrite(Long id, Long seenVersion, Consumer<Payout> write) {
        Optional<Payout> row = payouts.lockById(id);
        if (row.isEmpty()) {
            return OperatorWrite.notFound();
        }
        Payout payout = row.get();
        if (payout.getStatus() != PayoutStatus.NEEDS_ATTENTION) {
            return new OperatorWrite(OperatorWrite.Kind.NOT_WAITING, PayoutSnapshot.of(payout));
        }
        if (!payout.getVersion().equals(seenVersion)) {
            return new OperatorWrite(OperatorWrite.Kind.CHANGED, PayoutSnapshot.of(payout));
        }
        write.accept(payout);
        return new OperatorWrite(OperatorWrite.Kind.APPLIED, PayoutSnapshot.of(payout));
    }

    private void apply(Payout payout, StepOutcome outcome, Instant now) {
        switch (outcome) {
            case StepOutcome.Transferred transferred -> {
                boolean moved = payout.recordTransfer(
                        transferred.stripeAccountId(),
                        transferred.stripeTransferId(),
                        now
                );
                require(payout, moved);
            }
            case StepOutcome.Returned returned -> {
                require(payout, payout.returnBeforeTransfer(returned.reason(), now));
                outbox.publishReturned(payout, now);
            }
            case StepOutcome.PayoutCreated created ->
                    require(payout, payout.recordPayout(created.stripePayoutId(), now));
            case StepOutcome.FailedPayoutAdopted failed -> {
                require(payout, payout.recordPayout(failed.stripePayoutId(), now));
                require(payout, payout.startReversal(PayoutReason.PAYOUT_FAILED, failed.failureCode(), now));
                outbox.publishFailed(payout, now);
                resetBankAfterFailure(payout, failed.failedDestinationId());
            }
            case StepOutcome.Paid _ -> {
                require(payout, payout.markPaid(now));
                outbox.publishPaid(payout, now);
            }
            case StepOutcome.ReversalStarted reversal -> {
                require(payout, payout.startReversal(reversal.reason(), reversal.failureCode(), now));
                outbox.publishFailed(payout, now);
            }
            case StepOutcome.BankAccountRejected rejected -> {
                boolean moved = payout.startReversal(
                        PayoutReason.BANK_ACCOUNT_REJECTED,
                        rejected.providerCode(),
                        now
                );
                require(payout, moved);
                // Only the bank account the claim saw: a user who set another one since keeps it.
                if (rejected.externalAccountId() != null) {
                    accounts.lockById(rejected.payoutAccountId())
                            .filter(account -> rejected.externalAccountId().equals(account.getExternalAccountId()))
                            .ifPresent(account -> account.updateBankState(BankAccountState.NOT_SET));
                }
                outbox.publishFailed(payout, now);
            }
            case StepOutcome.Reversed reversed -> {
                require(payout, payout.recordReversal(reversed.stripeReversalId(), now));
                outbox.publishReturned(payout, now);
            }
            case StepOutcome.RetryUnknown retry -> payout.deferAttempt(retry.nextAttemptAt());
            case StepOutcome.RetryRefused retry -> {
                payout.raiseKeyGen();
                payout.deferAttempt(retry.nextAttemptAt());
            }
            case StepOutcome.KeySpent _ -> {
                payout.raiseKeyGen();
                payout.deferAttempt(now);
            }
            case StepOutcome.Attention attention -> {
                if (attention.raiseKeyGen()) {
                    payout.raiseKeyGen();
                }
                require(payout, payout.needAttention(attention.cause(), attention.detail(), now));
            }
            case StepOutcome.Released _ -> payout.releaseClaim();
            case StepOutcome.Postponed postponed -> payout.postpone(postponed.nextAttemptAt());
        }
    }

    /**
     * A mutator that refuses its transition while the row holds this claim's token is a defect: the step was decided
     * for the status the claim took. The transaction rolls back and the claim lapses.
     */
    private static void require(Payout payout, boolean moved) {
        if (!moved) {
            throw new IllegalStateException(
                    "Payout " + payout.getTransactionReference() + " refused a transition under its own claim"
            );
        }
    }
}
