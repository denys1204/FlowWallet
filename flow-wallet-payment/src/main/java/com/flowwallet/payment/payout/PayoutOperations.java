package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.PayoutProvider;
import com.flowwallet.payment.provider.dto.ProviderPayout;
import com.flowwallet.payment.provider.dto.ProviderTransfer;
import com.flowwallet.payment.provider.exception.PayoutLookupException;
import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The operator's actions on a payout in {@code NEEDS_ATTENTION}, each in a fixed order: read the row, check its
 * state, wait out the quiet period, ask Stripe with no transaction open, then write through {@link PayoutStore} only
 * while the row's {@code version} is the one read before. Deliberately not {@code @Transactional}, so no connection is
 * held while Stripe answers. It never creates anything at Stripe: only the executor's claimed steps do, so while a row
 * waits, nothing new can start for its reference, and what a check found stays true until the write.
 * See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutOperations {
    private final PayoutStore store;
    private final PayoutProvider provider;
    private final PayoutProperties properties;
    private final MeterRegistry meters;

    /**
     * An action's answer: the payout after the write, and for a return from {@code REVERSING} what stays in the
     * connected account as the platform's loss.
     */
    record Result(PayoutSnapshot payout, @Nullable BigDecimal leftInConnectedAccount) {
    }

    List<PayoutSnapshot> awaiting(int limit) {
        return store.findAwaitingOperator(limit);
    }

    long countAwaiting(PayoutStatus from) {
        return store.countAwaitingOperator(from);
    }

    PayoutSnapshot payout(String reference) {
        return store.findForOperator(reference).orElseThrow(PayoutNotFoundException::new);
    }

    Result act(String reference, PayoutAction action) {
        try {
            Result result = switch (action) {
                case REQUEUE -> requeue(reference);
                case RETURN_WITHOUT_REVERSAL -> returnWithoutReversal(reference);
                case REVERSE -> reverse(reference);
            };
            count(action, "applied");
            return result;
        } catch (PayoutNotFoundException | PayoutActionRefusedException e) {
            count(action, "refused");
            throw e;
        } catch (PayoutActionBusyException e) {
            count(action, "retry");
            throw e;
        } catch (PayoutCheckUnavailableException e) {
            count(action, "stripe_unavailable");
            throw e;
        }
    }

    /**
     * Needs no Stripe check and no quiet period: the step looks for what Stripe holds before it creates, under the
     * key generation it had, and a request of the last try still running is answered by that key.
     */
    private Result requeue(String reference) {
        PayoutSnapshot before = waiting(reference);
        PayoutSnapshot after = written(store.requeue(reference, Instant.now()));
        log.info(
                "Operator requeued payout {} to {} (was {}: {})",
                reference,
                after.status(),
                before.attentionCause(),
                before.attentionDetail()
        );
        return new Result(after, null);
    }

    private Result returnWithoutReversal(String reference) {
        PayoutSnapshot payout = waiting(reference);
        if (payout.attentionFrom() == PayoutStatus.TRANSFERRED) {
            throw PayoutActionRefusedException.transferStillOut(payout);
        }
        requireQuiet(payout);
        BigDecimal left = null;
        try {
            if (payout.attentionFrom() == PayoutStatus.ACCEPTED) {
                ProviderTransfer open = transferNotReversed(provider.findTransfers(reference));
                if (open != null) {
                    throw PayoutActionRefusedException.transferInGroup(open);
                }
            } else {
                ProviderPayout live = mayReachTheBank(payoutsOf(payout, true));
                if (live != null) {
                    throw PayoutActionRefusedException.livePayout(live);
                }
                String transferId = Objects.requireNonNull(payout.stripeTransferId(), "stripeTransferId");
                ProviderTransfer transfer = provider.retrieveTransfer(transferId);
                if (reversedInFull(transfer, payout.amount())) {
                    throw PayoutActionRefusedException.alreadyReversed(transferId);
                }
                left = payout.amount().subtract(transfer.amountReversed());
            }
        } catch (PayoutLookupException e) {
            throw new PayoutCheckUnavailableException(e);
        }

        PayoutSnapshot after = written(store.operatorReturn(payout.id(), payout.version(), Instant.now()));
        if (left == null) {
            log.warn(
                    "Operator returned payout {} from ACCEPTED; Stripe holds no transfer for it that is not reversed",
                    reference
            );
        } else {
            log.warn(
                    "Operator returned payout {} from REVERSING without a reversal; {} {} stays in connected "
                            + "account {} as the platform's loss",
                    reference,
                    render(left, payout.currency()),
                    payout.currency(),
                    payout.stripeAccountId()
            );
        }
        return new Result(after, left);
    }

    private Result reverse(String reference) {
        PayoutSnapshot payout = waiting(reference);
        if (payout.attentionFrom() == PayoutStatus.ACCEPTED) {
            throw PayoutActionRefusedException.nothingTransferred();
        }
        if (payout.attentionFrom() == PayoutStatus.REVERSING) {
            throw PayoutActionRefusedException.alreadyReversing();
        }
        requireQuiet(payout);
        try {
            // A row in TRANSFERRED holds no payout id: recording one moves it on to PAYOUT_CREATED.
            List<ProviderPayout> live = payoutsOf(payout, false).stream()
                    .filter(found -> found.status().mayReachTheBank())
                    .toList();
            if (live.size() == 1 && sameTerms(live.getFirst(), payout)) {
                // The payout step's lookup adopts exactly this payout after a requeue.
                throw PayoutActionRefusedException.livePayoutToAdopt(live.getFirst());
            }
            if (!live.isEmpty()) {
                throw PayoutActionRefusedException.livePayout(live.getFirst());
            }
        } catch (PayoutLookupException e) {
            throw new PayoutCheckUnavailableException(e);
        }

        PayoutSnapshot after = written(store.operatorReverse(payout.id(), payout.version(), Instant.now()));
        log.warn(
                "Operator sent payout {} from TRANSFERRED to its reversal; Stripe shows no payout of transfer {} that "
                        + "may reach the bank",
                reference,
                payout.stripeTransferId()
        );
        return new Result(after, null);
    }

    private PayoutSnapshot waiting(String reference) {
        PayoutSnapshot payout = payout(reference);
        if (payout.status() != PayoutStatus.NEEDS_ATTENTION) {
            throw PayoutActionRefusedException.notWaiting(payout);
        }
        return payout;
    }

    /**
     * The payout's own window of the account's payouts, as the executor lists it, and with {@code ownById} the payout
     * the row recorded when the listing lacks it, such as one whose metadata was edited.
     */
    private List<ProviderPayout> payoutsOf(PayoutSnapshot payout, boolean ownById) {
        String accountId = Objects.requireNonNull(payout.stripeAccountId(), "stripeAccountId");
        List<ProviderPayout> found = new ArrayList<>(provider.findPayouts(
                accountId,
                payout.reference(),
                PayoutSteps.payoutsListedSince(payout.transferredAt())
        ));
        String own = payout.stripePayoutId();
        if (ownById && own != null && found.stream().noneMatch(listed -> listed.id().equals(own))) {
            found.add(provider.retrievePayout(accountId, own));
        }
        return found;
    }

    /**
     * The quiet period after the hand-over. A row enters {@code NEEDS_ATTENTION} right after its last try's Stripe
     * calls, one of which may have timed out on this side while Stripe still runs it; the executor waits
     * {@code unknown-backoff} after such an outcome for the same reason, so a money action waits as long.
     */
    private void requireQuiet(PayoutSnapshot payout) {
        Instant until = quietUntil(payout, properties.getExecutor().getUnknownBackoff(), Instant.now());
        if (until != null) {
            throw PayoutActionBusyException.quietPeriod(payout.updatedAt(), until);
        }
    }

    /**
     * The end of the quiet period, or null once it has passed.
     */
    static @Nullable Instant quietUntil(PayoutSnapshot payout, Duration backoff, Instant now) {
        Instant until = payout.updatedAt().plus(backoff);
        return now.isBefore(until) ? until : null;
    }

    /**
     * A transfer in the payout's group that still holds money in a connected account; one reversed in full by hand
     * does not block a return.
     */
    static @Nullable ProviderTransfer transferNotReversed(List<ProviderTransfer> transfers) {
        return transfers.stream()
                .filter(transfer -> transfer.amountReversed().compareTo(transfer.amount()) < 0)
                .findFirst()
                .orElse(null);
    }

    /**
     * A payout that may still reach the bank: every status but failed and canceled, an unknown one included.
     */
    static @Nullable ProviderPayout mayReachTheBank(List<ProviderPayout> payouts) {
        return payouts.stream().filter(found -> found.status().mayReachTheBank()).findFirst().orElse(null);
    }

    /**
     * Whether the payout step would adopt a payout it finds: the terms it compares are the amount and the currency.
     */
    static boolean sameTerms(ProviderPayout found, PayoutSnapshot payout) {
        return found.amount().compareTo(payout.amount()) == 0 && found.currency().equalsIgnoreCase(payout.currency());
    }

    static boolean reversedInFull(ProviderTransfer transfer, BigDecimal amount) {
        return transfer.amountReversed().compareTo(amount) >= 0;
    }

    /**
     * An amount at the currency's payout scale, or finer for one off it, so neither an answer nor a log line fails on
     * a row it shows.
     */
    static String render(BigDecimal amount, String currency) {
        int scale = Math.max(StripeCurrencyRules.payoutScale(currency), amount.stripTrailingZeros().scale());
        return amount.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static PayoutSnapshot written(OperatorWrite write) {
        return switch (write.kind()) {
            case APPLIED -> Objects.requireNonNull(write.payout(), "payout");
            case NOT_FOUND -> throw new PayoutNotFoundException();
            case NOT_WAITING -> throw PayoutActionRefusedException.notWaiting(
                    Objects.requireNonNull(write.payout(), "payout")
            );
            case CHANGED -> throw PayoutActionBusyException.changed();
        };
    }

    private void count(PayoutAction action, String outcome) {
        meters.counter("payment.payouts.operator.actions", "action", action.metricName(), "outcome", outcome)
                .increment();
    }
}
