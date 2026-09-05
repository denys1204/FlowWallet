package com.flowwallet.payment.provider.stripe;

import java.util.Locale;
import java.util.Set;

/**
 * How much precision Stripe accepts for a currency, and what power of ten converts a major-unit amount
 * into the minor unit Stripe charges in.
 * <p>
 * The two numbers must be decided together. Deciding the exponent alone lets an amount with more decimals
 * than the currency has be rounded on the way out, so the customer is charged one figure and credited
 * another. Deciding the accepted precision alone lets a correctly-rounded amount be sent at the wrong scale.
 * <p>
 * The table is authoritative and the JDK is never consulted, which is deliberate. {@code Currency}
 * reports the ISO exponent, and for Stripe's purposes ISO is wrong in both directions: it gives MGA two
 * decimals where Stripe treats it as zero-decimal, which would multiply a charge by a hundred, and it gives
 * ISK none where Stripe expects two, which would divide one by a hundred. A table that is never crossed with
 * the JDK also cannot be broken by a JDK update revising an ISO figure.
 *
 * @see <a href="https://docs.stripe.com/currencies#zero-decimal">Stripe: zero-decimal currencies</a>
 */
public final class StripeCurrencyRules {
    private StripeCurrencyRules() {
    }

    /**
     * Charged as whole units — Stripe expects the major-unit figure unchanged.
     */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF"
    );

    /**
     * Charged in thousandths. Restricted to the five that Stripe documents; IQD and LYD also carry three
     * decimals in ISO but are deliberately absent, because an ISO fact asserted as a Stripe fact is the
     * mistake this class exists to avoid. An unlisted currency is charged at the default exponent.
     */
    private static final Set<String> THREE_DECIMAL = Set.of("BHD", "JOD", "KWD", "OMR", "TND");

    private static final int DEFAULT_EXPONENT = 2;

    /**
     * Nothing finer than a hundredth is accepted, whatever the currency's exponent. For the three-decimal
     * currencies this is stricter than the exponent, and that is on purpose: Stripe requires their minor
     * amounts to end in zero, and capping the accepted precision at two decimals makes every amount we can
     * accept satisfy that by construction, without a second rule to keep in step.
     */
    private static final int MAX_ACCEPTED_SCALE = 2;

    public static CurrencyRule of(String currency) {
        String code = currency.toUpperCase(Locale.ROOT);
        int exponent = ZERO_DECIMAL.contains(code)
                ? 0
                : THREE_DECIMAL.contains(code) ? 3 : DEFAULT_EXPONENT;

        return new CurrencyRule(Math.min(exponent, MAX_ACCEPTED_SCALE), exponent);
    }

    /**
     * @param acceptedScale    most decimal places an amount in this currency may carry
     * @param transmitExponent power of ten taking a major-unit amount to the unit Stripe charges in
     */
    public record CurrencyRule(int acceptedScale, int transmitExponent) {
    }
}
