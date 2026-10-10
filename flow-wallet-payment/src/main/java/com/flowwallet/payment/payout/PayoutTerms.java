package com.flowwallet.payment.payout;

import com.flowwallet.payment.provider.stripe.StripeCurrencyRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * The terms configuration sets for a payout: the currency list, its minimum and the payout grid. A command that
 * misses them after its debit is returned with {@code TERMS_REFUSED}.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md and
 * docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md.
 */
@Component
@RequiredArgsConstructor
public class PayoutTerms {
    /**
     * The wallet's bound, which {@code NUMERIC(19,4)} holds, so an amount the wallet could never debit is refused here
     * as well.
     */
    private static final long MAX_INTEGER_DIGITS = 15;

    private final PayoutProperties properties;

    /**
     * Whether the amount has at most the integer digits a payout row holds. Subtracted in long: a scale near
     * {@code Integer.MIN_VALUE} would wrap an int difference to a negative count and let a huge amount through.
     */
    static boolean fitsIntegerDigits(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return (long) stripped.precision() - stripped.scale() <= MAX_INTEGER_DIGITS;
    }

    /**
     * @return why the amount cannot be paid out in the currency, for a log line or a problem detail; empty when it
     *         can. The text names the rule and never repeats the amount.
     */
    public Optional<String> refusal(String currency, BigDecimal amount) {
        String code = currency.toUpperCase(Locale.ROOT);
        Optional<PayoutProperties.PayoutCurrency> listed = properties.getCurrencies().stream()
                .filter(entry -> entry.getCode().equals(code))
                .findFirst();
        if (listed.isEmpty()) {
            return Optional.of("Withdrawals are not offered in this currency");
        }
        if (amount.compareTo(listed.get().getMinAmount()) < 0) {
            return Optional.of("The amount is below the smallest withdrawal in this currency");
        }
        if (amount.stripTrailingZeros().scale() > StripeCurrencyRules.payoutScale(code)) {
            return Optional.of("The amount has more decimals than a payout in this currency allows");
        }
        return Optional.empty();
    }
}
