package com.flowwallet.payment.payout;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The operator's exit from {@code NEEDS_ATTENTION}: GET lists the waiting payouts, GET of a reference shows one payout
 * in any status, and POST of a reference and an action requeues, returns or reverses it. Web only, on Payment
 * Service's own port, and in no default exposure: its writes credit wallets and it authenticates nobody, so it is only
 * as protected as that port. A refusal is a problem response whose detail names the action to use instead.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md and
 * docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
@Component
@WebEndpoint(id = "payouts")
@RequiredArgsConstructor
public class PayoutsEndpoint {
    static final int LIST_LIMIT = 100;

    private static final List<PayoutStatus> STEPS = List.of(
            PayoutStatus.ACCEPTED,
            PayoutStatus.TRANSFERRED,
            PayoutStatus.REVERSING
    );

    private final PayoutOperations operations;

    /**
     * The waiting payouts, the longest-waiting first, and how many wait from each step.
     */
    public record AttentionList(
            long needsAttention,
            Map<String, Long> byFrom,
            boolean truncated,
            List<PayoutView> payouts
    ) {
    }

    /**
     * A payout as an operator needs it, without its user id. Amounts are strings at the currency's payout scale and
     * times are ISO-8601 strings, so the shape does not depend on the actuator's JSON mapper.
     *
     * @param since   the hand-over to an operator; set only in {@code NEEDS_ATTENTION}
     * @param actions what the payout's state allows, before Stripe is asked
     */
    public record PayoutView(
            String reference,
            String status,
            @Nullable String attentionFrom,
            @Nullable String cause,
            @Nullable String detail,
            @Nullable String since,
            String amount,
            String currency,
            @Nullable String reasonCode,
            @Nullable String failureCode,
            int keyGen,
            int attempts,
            @Nullable String nextAttemptAt,
            @Nullable String stripeAccountId,
            @Nullable String stripeTransferId,
            @Nullable String stripePayoutId,
            @Nullable String stripeReversalId,
            @Nullable String transferredAt,
            @Nullable String paidAt,
            String createdAt,
            List<String> actions
    ) {
        static PayoutView of(PayoutSnapshot payout) {
            boolean waiting = payout.status() == PayoutStatus.NEEDS_ATTENTION;
            return new PayoutView(
                    payout.reference(),
                    payout.status().name(),
                    name(payout.attentionFrom()),
                    name(payout.attentionCause()),
                    payout.attentionDetail(),
                    waiting ? text(payout.updatedAt()) : null,
                    PayoutOperations.render(payout.amount(), payout.currency()),
                    payout.currency(),
                    name(payout.reasonCode()),
                    payout.failureCode(),
                    payout.keyGen(),
                    payout.attempts(),
                    payout.status().isExecutorStep() ? text(payout.nextAttemptAt()) : null,
                    payout.stripeAccountId(),
                    payout.stripeTransferId(),
                    payout.stripePayoutId(),
                    payout.stripeReversalId(),
                    text(payout.transferredAt()),
                    text(payout.paidAt()),
                    payout.createdAt().toString(),
                    PayoutAction.allowedFrom(waiting ? payout.attentionFrom() : null)
                            .stream()
                            .map(PayoutAction::path)
                            .toList()
            );
        }
    }

    /**
     * @param leftInConnectedAccount set only for a return from {@code REVERSING}: the part of the transfer no reversal
     *                               brought back, which the platform loses
     */
    public record ActionResult(String action, PayoutView payout, @Nullable String leftInConnectedAccount) {
    }

    @ReadOperation
    public AttentionList attention() {
        Map<String, Long> byFrom = new LinkedHashMap<>();
        STEPS.forEach(step -> byFrom.put(step.name(), operations.countAwaiting(step)));
        long waiting = byFrom.values().stream().mapToLong(Long::longValue).sum();
        // One row more than shown says whether the list is cut, whatever moved between the counts and the read.
        List<PayoutSnapshot> read = operations.awaiting(LIST_LIMIT + 1);
        List<PayoutView> payouts = read.stream().limit(LIST_LIMIT).map(PayoutView::of).toList();
        return new AttentionList(waiting, byFrom, read.size() > LIST_LIMIT, payouts);
    }

    @ReadOperation
    public PayoutView payout(@Selector String reference) {
        return PayoutView.of(operations.payout(fold(reference)));
    }

    @WriteOperation
    public ActionResult act(@Selector String reference, @Selector String action) {
        PayoutAction chosen = PayoutAction.of(action).orElseThrow(UnknownPayoutActionException::new);
        PayoutOperations.Result result = operations.act(fold(reference), chosen);
        BigDecimal left = result.leftInConnectedAccount();
        return new ActionResult(
                chosen.path(),
                PayoutView.of(result.payout()),
                left == null ? null : PayoutOperations.render(left, result.payout().currency())
        );
    }

    /**
     * References are stored lower-cased; one that matches no row is a 404 whatever its shape.
     */
    private static String fold(String reference) {
        return reference.toLowerCase(Locale.ROOT);
    }

    private static @Nullable String name(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static @Nullable String text(@Nullable Instant time) {
        return time == null ? null : time.toString();
    }
}
