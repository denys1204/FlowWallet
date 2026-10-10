package com.flowwallet.payment.payout;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What an operator can do with a payout in {@code NEEDS_ATTENTION}, by the step it came from.
 * See docs/adr/0037-operator-actions-decide-from-the-row-and-stripe-as-they-are.md.
 */
public enum PayoutAction {
    REQUEUE("requeue"),
    RETURN_WITHOUT_REVERSAL("return-without-reversal"),
    REVERSE("reverse");

    private final String path;

    PayoutAction(String path) {
        this.path = path;
    }

    /**
     * The action's name in the endpoint's path.
     */
    public String path() {
        return path;
    }

    /**
     * The action's name in metrics.
     */
    public String metricName() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<PayoutAction> of(String path) {
        return Arrays.stream(values()).filter(action -> action.path.equals(path)).findFirst();
    }

    /**
     * The actions a payout waiting from this step can take, before Stripe is asked: a return never from
     * {@code TRANSFERRED}, whose transfer a reversal can still bring back, and a reversal only from there.
     */
    static List<PayoutAction> allowedFrom(@Nullable PayoutStatus attentionFrom) {
        if (attentionFrom == null) {
            return List.of();
        }
        return switch (attentionFrom) {
            case ACCEPTED, REVERSING -> List.of(REQUEUE, RETURN_WITHOUT_REVERSAL);
            case TRANSFERRED -> List.of(REQUEUE, REVERSE);
            default -> List.of();
        };
    }
}
