package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.PayoutCallResult;
import com.flowwallet.payment.provider.dto.PayoutRefusal;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderPayoutStatus;
import com.flowwallet.payment.provider.dto.ProviderTransfer;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.payment.provider.stripe.config.StripeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Works one claimed step at Stripe and decides its outcome; it writes nothing and holds no transaction.
 * <p>
 * A step that an earlier claim marked attempted first looks for what Stripe may already hold, because a crash or a
 * timeout records no outcome: a transfer by its group, a payout among the connected account's payouts since an hour
 * before the transfer, a reversal by the transfer's reversed amount. A found object is adopted only when its terms
 * are the payout's. An unknown outcome keeps the key generation and backs off; only a definite refusal moves the
 * key on. Every move out of {@code TRANSFERRED} into {@code REVERSING} follows a listing in the same try that found
 * no payout that may still reach the bank, and {@code REVERSING} lists again before it reverses.
 * <p>
 * Rules ADR 0035 leaves implicit: more than one transfer, or more than one live payout, for a reference needs an
 * operator; a found object with other terms needs an operator; a failed or canceled payout found by the listing is
 * recorded and its transfer reversed; a payout refused for a reason that does not clear on its own reverses with
 * {@code PAYOUT_FAILED}. Before each call the step checks that its lease has room for it, and gives the claim up
 * otherwise. See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PayoutSteps {
    /**
     * The most calls one step makes: the reversal step's listing, and the retrieve and create of
     * {@link PayoutProvider#reverseTransfer}. A listing counts as one call, which holds while a connected account has
     * at most one page (100) of payouts since an hour before its transfer, and a group at most one page of transfers.
     */
    static final int MAX_CALLS_PER_STEP = 3;

    /**
     * Time a claim keeps after its last call, for recording the outcome and for clock skew between instances.
     */
    static final Duration LEASE_MARGIN = Duration.ofSeconds(10);

    /**
     * How far before the transfer the payout listing reaches, for a payout created just before the transfer was
     * recorded.
     */
    static final Duration LOOKBACK = Duration.ofHours(1);

    private final PayoutProvider provider;
    private final PayoutProperties properties;
    private final StripeProperties stripe;

    StepOutcome work(ClaimedPayout claim) {
        try {
            return switch (claim.step()) {
                case ACCEPTED -> transfer(claim);
                case TRANSFERRED -> payout(claim);
                case REVERSING -> reverse(claim);
                default -> throw new IllegalStateException("Payout " + claim.reference() + " is in no executor step");
            };
        } catch (PayoutLookupException e) {
            return unknown(claim, e.getMessage());
        } catch (RuntimeException e) {
            // Anything unexpected: nothing settled, so it counts as an unknown outcome and reaches NEEDS_ATTENTION at
            // the limit instead of looping. Its stack is logged, since only it shows where the defect is.
            log.warn("Payout {} {} failed unexpectedly", claim.reference(), claim.step(), e);
            return unknown(claim, e.toString());
        }
    }

    private StepOutcome transfer(ClaimedPayout claim) {
        if (!claim.earlierAttempt() && !claim.bankReady()) {
            return new StepOutcome.Returned(PayoutReason.BANK_ACCOUNT_NOT_SET, null);
        }
        if (claim.earlierAttempt()) {
            if (!hasRoom(claim, 1)) {
                return released();
            }
            List<ProviderTransfer> found = provider.findTransfers(claim.reference());
            if (found.size() > 1) {
                return attention(AttentionCause.MULTIPLE, found.size() + " transfers in the group");
            }
            if (found.size() == 1) {
                ProviderTransfer transfer = found.getFirst();
                return matches(claim, transfer)
                        ? new StepOutcome.Transferred(transfer.destinationAccountId(), transfer.id(), true)
                        : attention(AttentionCause.MISMATCH, "transfer " + transfer.id() + " has other terms");
            }
        }
        // Only a later try gets here without an account, since READY needs both Stripe ids: nothing was sent.
        if (claim.bank() == null || claim.bank().stripeAccountId() == null) {
            return new StepOutcome.Returned(PayoutReason.BANK_ACCOUNT_NOT_SET, null);
        }
        if (!hasRoom(claim, 1)) {
            return released();
        }
        // A later try creates under the same key even when the bank account is no longer ready: only the key settles
        // a request Stripe may still be running, and the payout step reverses the transfer.
        return switch (provider.createTransfer(
                claim.reference(),
                claim.bank().stripeAccountId(),
                claim.amount(),
                claim.currency(),
                claim.keyGen()
        )) {
            case PayoutCallResult.Done<ProviderTransfer>(ProviderTransfer transfer) ->
                    new StepOutcome.Transferred(transfer.destinationAccountId(), transfer.id(), false);
            case PayoutCallResult.Refused<ProviderTransfer>(PayoutRefusal refusal, String code) ->
                    refusal == PayoutRefusal.INSUFFICIENT_FUNDS
                            ? new StepOutcome.Returned(PayoutReason.PLATFORM_FUNDS_SHORT, code)
                            : new StepOutcome.Returned(PayoutReason.TRANSFER_REFUSED, code);
            case PayoutCallResult.Unknown<ProviderTransfer>(String cause) -> unknown(claim, cause);
            case PayoutCallResult.Defect<ProviderTransfer>(String cause) -> attention(AttentionCause.DEFECT, cause);
        };
    }

    private StepOutcome payout(ClaimedPayout claim) {
        boolean listed = false;
        if (claim.earlierAttempt() || !claim.bankReady()) {
            if (!hasRoom(claim, 1)) {
                return released();
            }
            Optional<StepOutcome> judged = judge(claim, listPayouts(claim));
            if (judged.isPresent()) {
                return judged.get();
            }
            listed = true;
        }
        if (!claim.bankReady()) {
            return new StepOutcome.ReversalStarted(PayoutReason.BANK_ACCOUNT_NOT_SET, null);
        }
        if (!hasRoom(claim, 1)) {
            return released();
        }
        return switch (provider.createPayout(
                claim.stripeAccountId(),
                claim.reference(),
                claim.amount(),
                claim.currency(),
                claim.keyGen()
        )) {
            case PayoutCallResult.Done<ProviderPayout>(ProviderPayout payout) ->
                    new StepOutcome.PayoutCreated(payout.id(), false);
            case PayoutCallResult.Refused<ProviderPayout>(PayoutRefusal refusal, String code) ->
                    payoutRefused(claim, refusal, code, listed);
            case PayoutCallResult.Unknown<ProviderPayout>(String cause) -> unknown(claim, cause);
            case PayoutCallResult.Defect<ProviderPayout>(String cause) -> attention(AttentionCause.DEFECT, cause);
        };
    }

    private StepOutcome payoutRefused(ClaimedPayout claim, PayoutRefusal refusal, String code, boolean listed) {
        return switch (refusal) {
            case INSUFFICIENT_FUNDS, LIMIT_REACHED -> claim.attempts() + 1 < executor().getPayoutRetryAttempts()
                    ? new StepOutcome.RetryRefused(Instant.now().plus(executor().getPayoutRetryInterval()), code)
                    : afterListing(claim, listed, new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, code));
            // On a later try the refusal may be Stripe's stored answer to the earlier one, about an older bank account.
            case BANK_ACCOUNT -> afterListing(
                    claim,
                    listed,
                    new StepOutcome.BankAccountRejected(
                            code,
                            claim.bank().accountId(),
                            claim.earlierAttempt() ? null : claim.bank().externalAccountId()
                    )
            );
            // account_invalid, amount_too_small, amount_too_large: none clears on its own.
            case OTHER -> afterListing(
                    claim,
                    listed,
                    new StepOutcome.ReversalStarted(PayoutReason.PAYOUT_FAILED, code)
            );
        };
    }

    /**
     * The reversal a refusal leads to, after a listing in this try if none ran yet: a live payout found there is
     * adopted instead.
     */
    private StepOutcome afterListing(ClaimedPayout claim, boolean listed, StepOutcome reversal) {
        if (listed) {
            return reversal;
        }
        if (!hasRoom(claim, 1)) {
            return released();
        }
        return judge(claim, listPayouts(claim)).orElse(reversal);
    }

    private StepOutcome reverse(ClaimedPayout claim) {
        if (!hasRoom(claim, 1)) {
            return released();
        }
        // Any live payout can only be a second one for the reference: money that may reach a bank is never reversed.
        List<ProviderPayout> live = listPayouts(claim).stream()
                .filter(payout -> payout.status().mayReachTheBank())
                .toList();
        if (!live.isEmpty()) {
            String id = live.getFirst().id();
            return attention(AttentionCause.LIVE_PAYOUT, "payout " + id + " may still reach the bank");
        }
        if (!hasRoom(claim, 2)) {
            return released();
        }
        return switch (provider.reverseTransfer(
                claim.stripeTransferId(),
                claim.reference(),
                claim.amount(),
                claim.currency(),
                claim.keyGen()
        )) {
            case PayoutCallResult.Done<String>(String reversalId) -> new StepOutcome.Reversed(reversalId);
            case PayoutCallResult.Refused<String>(PayoutRefusal refusal, String code) ->
                    claim.attempts() + 1 < executor().getReversalRetryAttempts()
                            ? new StepOutcome.RetryRefused(
                                    Instant.now().plus(executor().getReversalRetryInterval()),
                                    code
                            )
                            : new StepOutcome.Attention(AttentionCause.REVERSAL_REFUSED, true, code);
            case PayoutCallResult.Unknown<String>(String cause) -> unknown(claim, cause);
            case PayoutCallResult.Defect<String>(String cause) -> attention(AttentionCause.DEFECT, cause);
        };
    }

    /**
     * Decides the payout step from the connected account's payouts for the reference, or nothing when there are none.
     * Stripe lists the newest first.
     */
    private static Optional<StepOutcome> judge(ClaimedPayout claim, List<ProviderPayout> found) {
        List<ProviderPayout> live = found.stream()
                .filter(payout -> payout.status().mayReachTheBank())
                .toList();
        if (live.size() > 1) {
            return Optional.of(attention(AttentionCause.MULTIPLE, live.size() + " live payouts"));
        }
        if (live.size() == 1) {
            ProviderPayout payout = live.getFirst();
            return Optional.of(matches(claim, payout)
                    ? new StepOutcome.PayoutCreated(payout.id(), true)
                    : attention(AttentionCause.MISMATCH, "payout " + payout.id() + " has other terms"));
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProviderPayout failed = found.getFirst();
        String failedDestination = failed.status() == ProviderPayoutStatus.FAILED ? failed.destinationId() : null;
        return Optional.of(matches(claim, failed)
                ? new StepOutcome.FailedPayoutAdopted(failed.id(), failed.failureCode(), failedDestination)
                : attention(AttentionCause.MISMATCH, "payout " + failed.id() + " has other terms"));
    }

    private List<ProviderPayout> listPayouts(ClaimedPayout claim) {
        return provider.findPayouts(
                claim.stripeAccountId(),
                claim.reference(),
                payoutsListedSince(claim.transferredAt())
        );
    }

    /**
     * Where a listing of a transfer's payouts starts: {@link #LOOKBACK} before the transfer, or from the beginning when
     * its time is unknown. The operator's checks list the same window.
     */
    static Instant payoutsListedSince(@Nullable Instant transferredAt) {
        return transferredAt == null ? Instant.EPOCH : transferredAt.minus(LOOKBACK);
    }

    private StepOutcome unknown(ClaimedPayout claim, String cause) {
        if (claim.attempts() + 1 >= executor().getUnknownAttempts()) {
            return new StepOutcome.Attention(AttentionCause.UNKNOWN_LIMIT, false, cause);
        }
        Instant next = Instant.now().plus(executor().unknownBackoffAfter(claim.attempts()));
        return new StepOutcome.RetryUnknown(next, cause);
    }

    /**
     * Whether the lease leaves time for {@code calls} more Stripe calls and the margin after them.
     */
    private boolean hasRoom(ClaimedPayout claim, int calls) {
        Instant needed = Instant.now().plus(stripe.getApi().callBudget().multipliedBy(calls)).plus(LEASE_MARGIN);
        return !needed.isAfter(claim.claimedUntil());
    }

    private PayoutProperties.Executor executor() {
        return properties.getExecutor();
    }

    private static boolean matches(ClaimedPayout claim, ProviderTransfer transfer) {
        return claim.bank() != null
                && transfer.destinationAccountId() != null
                && transfer.destinationAccountId().equals(claim.bank().stripeAccountId())
                && transfer.amount().compareTo(claim.amount()) == 0
                && transfer.currency().equalsIgnoreCase(claim.currency())
                && transfer.amountReversed().signum() == 0;
    }

    private static boolean matches(ClaimedPayout claim, ProviderPayout payout) {
        return payout.amount().compareTo(claim.amount()) == 0 && payout.currency().equalsIgnoreCase(claim.currency());
    }

    private static StepOutcome released() {
        return new StepOutcome.Released("no room on the lease for the next Stripe call");
    }

    private static StepOutcome attention(AttentionCause cause, String detail) {
        return new StepOutcome.Attention(cause, false, detail);
    }
}
