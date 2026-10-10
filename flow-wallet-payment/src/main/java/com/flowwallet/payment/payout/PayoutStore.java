package com.flowwallet.payment.payout;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The executor's short transactions around its Stripe calls: the claim before them and the record after them, so no
 * connection is held while Stripe answers. The record locks the row and applies the outcome only while the row still
 * holds the claim's token; every transition clears the token, so an outcome another writer overtook changes nothing
 * and writes no second event. Rows are locked in the order payouts, then payout accounts.
 * See docs/adr/0006-short-transactions-across-bean-boundaries.md and
 * docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
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
            case StepOutcome.Attention attention -> {
                if (attention.raiseKeyGen()) {
                    payout.raiseKeyGen();
                }
                require(payout, payout.needAttention(now));
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
