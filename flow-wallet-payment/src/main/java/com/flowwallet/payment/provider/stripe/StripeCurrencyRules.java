package com.flowwallet.payment.provider.stripe;

import java.util.Locale;
import java.util.Set;

/**
 * How much precision Stripe accepts for a currency, and what power of ten converts a major-unit amount
 * into the minor unit Stripe charges in. Both come from this table and never from {@code java.util.Currency},
 * whose ISO exponents differ from Stripe's (MGA, ISK). See docs/adr/0015-currency-precision-and-no-rounding.md and
 * docs/adr/0023-isk-charged-in-whole-units.md.
 *
 * @see <a href="https://docs.stripe.com/currencies#zero-decimal">Stripe: zero-decimal currencies</a>
 */
public final class StripeCurrencyRules {
    private StripeCurrencyRules() {
    }

    /**
     * Charged as whole units. {@code com.flowwallet.wallet.api.AmountPrecision} keeps a copy of this list, of
     * {@link #WHOLE_UNITS_IN_HUNDREDTHS} and of {@link #MAX_ACCEPTED_SCALE} as its {@code DEFAULT_ACCEPTED_SCALE},
     * so a change here must be made there too.
     */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF"
    );

    /**
     * Charged as whole units written in hundredths: Stripe takes the amount in two-decimal form, and the decimals
     * must be 00 (https://docs.stripe.com/currencies, retrieved on 2026-09-27). So the accepted scale is 0 and the
     * transmit exponent 2. {@code com.flowwallet.wallet.api.AmountPrecision} keeps a copy of this list too.
     */
    private static final Set<String> WHOLE_UNITS_IN_HUNDREDTHS = Set.of("ISK");

    /**
     * Charged in thousandths: only the five that Stripe documents. IQD and LYD carry three decimals in ISO, not
     * in Stripe, and stay at the default exponent.
     */
    private static final Set<String> THREE_DECIMAL = Set.of("BHD", "JOD", "KWD", "OMR", "TND");

    private static final int DEFAULT_EXPONENT = 2;

    /**
     * Nothing finer than a hundredth is accepted, whatever the currency's exponent. For the three-decimal
     * currencies this makes every minor amount end in zero, as Stripe requires.
     */
    private static final int MAX_ACCEPTED_SCALE = 2;

    public static CurrencyRule of(String currency) {
        String code = currency.toUpperCase(Locale.ROOT);
        int exponent = ZERO_DECIMAL.contains(code)
                ? 0
                : THREE_DECIMAL.contains(code) ? 3 : DEFAULT_EXPONENT;

        int acceptedScale = WHOLE_UNITS_IN_HUNDREDTHS.contains(code) ? 0 : Math.min(exponent, MAX_ACCEPTED_SCALE);
        return new CurrencyRule(acceptedScale, exponent);
    }

    /**
     * @param acceptedScale    most decimal places an amount in this currency may carry
     * @param transmitExponent power of ten taking a major-unit amount to the unit Stripe charges in
     */
    public record CurrencyRule(int acceptedScale, int transmitExponent) {
    }
}
