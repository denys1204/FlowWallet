package com.flowwallet.payment.payout;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * What one executor step decided, for {@link PayoutStore#record} to apply. The step makes the Stripe calls and writes
 * nothing; the record applies the transition, its outbox event and the bookkeeping in one short transaction.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public sealed interface StepOutcome {
    /**
     * The transfer exists, made now or found by its group.
     */
    record Transferred(String stripeAccountId, String stripeTransferId, boolean adopted) implements StepOutcome {
    }

    /**
     * Nothing left the platform, so the money goes back without a reversal.
     */
    record Returned(PayoutReason reason, @Nullable String providerCode) implements StepOutcome {
    }

    /**
     * The payout to the bank exists, made now or found live among the account's payouts.
     */
    record PayoutCreated(String stripePayoutId, boolean adopted) implements StepOutcome {
    }

    /**
     * A failed or canceled payout for the reference was found: it is recorded and its transfer reversed.
     */
    record FailedPayoutAdopted(String stripePayoutId, @Nullable String failureCode) implements StepOutcome {
    }

    /**
     * The money is certain to come back, and the transfer is reversed next.
     */
    record ReversalStarted(PayoutReason reason, @Nullable String failureCode) implements StepOutcome {
    }

    /**
     * Stripe refused the payout for its bank account, which is set back to {@code NOT_SET} if it is still the one the
     * claim saw.
     *
     * @param externalAccountId the bank account the claim saw; null when the refusal may be the stored answer to an
     *                          earlier try, which Stripe gives under the same key and which says nothing about a bank
     *                          account set since, so none is reset
     */
    record BankAccountRejected(
            String providerCode,
            Long payoutAccountId,
            @Nullable String externalAccountId
    ) implements StepOutcome {
    }

    /**
     * The transfer is reversed, and the money is back on the platform.
     */
    record Reversed(String stripeReversalId) implements StepOutcome {
    }

    /**
     * No answer settled the step; it tries again under the same key generation.
     */
    record RetryUnknown(Instant nextAttemptAt, String cause) implements StepOutcome {
    }

    /**
     * Stripe refused the step for a reason that may clear; it tries again under a fresh key generation, because
     * Stripe answers the old key with the stored refusal.
     */
    record RetryRefused(Instant nextAttemptAt, String providerCode) implements StepOutcome {
    }

    /**
     * An operator takes over in {@code NEEDS_ATTENTION}.
     *
     * @param raiseKeyGen whether the step moves to a fresh key, so the first try after a requeue reaches Stripe
     */
    record Attention(AttentionCause cause, boolean raiseKeyGen, String detail) implements StepOutcome {
    }

    /**
     * The step gave its claim up when nothing it sent can have been created: before any call of this try, or after a
     * definite refusal. No attempt is counted, and the row is due again at once.
     */
    record Released(String why) implements StepOutcome {
    }

    /**
     * An outcome that settled nothing, seen while the executor is being interrupted: the call was cut short by the
     * shutdown itself, so no attempt is counted, but the row waits as long as after an unknown outcome, because Stripe
     * may still be running the request the next try's lookup must find.
     */
    record Postponed(Instant nextAttemptAt, String why) implements StepOutcome {
    }

    /**
     * The outcome's name in the executor's metrics.
     */
    default String label() {
        return switch (this) {
            case Transferred _ -> "transferred";
            case Returned _ -> "returned";
            case PayoutCreated _ -> "payout_created";
            case FailedPayoutAdopted _ -> "reversing";
            case ReversalStarted _ -> "reversing";
            case BankAccountRejected _ -> "reversing";
            case Reversed _ -> "reversed";
            case RetryUnknown _ -> "retry_unknown";
            case RetryRefused _ -> "retry_refused";
            case Attention _ -> "attention";
            case Released _ -> "released";
            case Postponed _ -> "postponed";
        };
    }

    /**
     * Whether the outcome counts towards the run's failures in a row: an answer that settled nothing.
     */
    default boolean isFailure() {
        return this instanceof RetryUnknown
                || (this instanceof Attention attention && attention.cause() == AttentionCause.UNKNOWN_LIMIT);
    }
}
