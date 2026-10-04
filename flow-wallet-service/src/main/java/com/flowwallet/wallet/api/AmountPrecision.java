package com.flowwallet.wallet.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * The grid an amount moved inside the wallet must sit on, and the single form it is stored and compared in.
 * <p>
 * The grid copies the accepted scale in Payment Service's {@code StripeCurrencyRules}: whole units for its
 * zero-decimal currencies and for ISK, two decimals for every other. An amount off the grid is refused, never
 * rounded. See docs/adr/0015-currency-precision-and-no-rounding.md and docs/adr/0023-isk-charged-in-whole-units.md.
 */
public final class AmountPrecision {
    /**
     * The integer room in {@code NUMERIC(19,4)}. No balance can hold a larger amount, so it gets a 400 here
     * instead of an "Insufficient funds" 422.
     */
    private static final int MAX_INTEGER_DIGITS = 15;

    /**
     * The ledger's scale: amounts are stored and compared at it, and responses render them at the currency's scale
     * instead (see {@link #render}).
     */
    private static final int LEDGER_SCALE = 4;

    private static final int DEFAULT_ACCEPTED_SCALE = 2;

    /**
     * Copied from {@code StripeCurrencyRules.ZERO_DECIMAL}, which points back here; a change to one list must be
     * made to both. Every other currency accepts two decimals there, the three-decimal ones included.
     */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF"
    );

    /**
     * Copied from {@code StripeCurrencyRules.WHOLE_UNITS_IN_HUNDREDTHS}: Stripe charges ISK in whole units, although
     * it takes the amount in hundredths, so a fractional ISK could never be paid in or out.
     */
    private static final Set<String> WHOLE_UNITS_IN_HUNDREDTHS = Set.of("ISK");

    private AmountPrecision() {
    }

    /**
     * Checks an amount against the ledger and its currency's grid, and returns it at the ledger's scale.
     * <p>
     * Both checks judge the amount without its trailing zeros, so 10.5000 USD and 1E+2 JPY pass. The size check
     * runs first, so 1E+999999999 is refused before {@code setScale} could expand it, and subtracts in
     * {@code long} because a scale near the {@code int} limit would overflow and let the largest amounts through.
     * {@code RoundingMode.UNNECESSARY} throws instead of rounding if the checks ever stop making the rescale exact.
     *
     * @param amount   a positive amount in major units; the sign is the request's concern
     * @param currency the upper-case ISO code of the wallet the amount moves in, as {@code Currencies.normalise}
     *                 returns it
     * @return the same value at scale 4
     * @throws InvalidAmountException if the amount has more than 15 integer digits or more decimal places than
     *                                the currency accepts
     */
    public static BigDecimal canonical(BigDecimal amount, String currency) {
        BigDecimal stripped = amount.stripTrailingZeros();

        if (!fitsIntegerDigits(stripped)) {
            throw InvalidAmountException.tooLarge();
        }
        int acceptedScale = acceptedScale(currency);
        if (stripped.scale() > acceptedScale) {
            throw InvalidAmountException.tooPrecise(currency, acceptedScale);
        }
        return stripped.setScale(LEDGER_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Whether {@link #canonical} would accept the amount, for a caller that records a refusal instead of throwing
     * one, such as the payment event consumer. The sign is not judged. An event's currency has not passed through
     * {@code Currencies.normalise}, so it is upper-cased here.
     */
    public static boolean isOnGrid(BigDecimal amount, String currency) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return fitsIntegerDigits(stripped) && stripped.scale() <= acceptedScale(currency.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether a {@code NUMERIC(19,4)} column holds the amount exactly. Postgres rounds a finer value and refuses a
     * larger one, so an amount that fails here must not be written to such a column as it is.
     */
    public static boolean fitsLedger(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return fitsIntegerDigits(stripped) && stripped.scale() <= LEDGER_SCALE;
    }

    /**
     * How many decimals the currency's amounts carry on the grid: 0 for the zero-decimal currencies and ISK, 2 for
     * every other. The three-decimal currencies such as KWD get 2, because the grid caps them there, so ISO's
     * {@code java.util.Currency} would disagree.
     *
     * @param currency an ISO code in either case
     */
    public static int decimals(String currency) {
        return acceptedScale(currency.toUpperCase(Locale.ROOT));
    }

    /**
     * Writes an amount as a plain decimal string at its currency's scale: 25.0000 USD as {@code "25.00"} and
     * 1000.0000 JPY as {@code "1000"}. Every response renders money through here, so a first answer built in memory
     * and a replay read back from the ledger come out the same whatever scale each value holds. A value finer than
     * the grid keeps its extra digits instead of being rounded; only a row written before its currency's grid
     * existed can hold one. See docs/adr/0030-amounts-in-responses-are-decimal-strings.md.
     *
     * @param currency an ISO code in either case
     */
    public static String render(BigDecimal amount, String currency) {
        BigDecimal stripped = amount.stripTrailingZeros();
        int scale = Math.max(decimals(currency), stripped.scale());
        return stripped.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static boolean fitsIntegerDigits(BigDecimal stripped) {
        return (long) stripped.precision() - stripped.scale() <= MAX_INTEGER_DIGITS;
    }

    private static int acceptedScale(String upperCaseCode) {
        return ZERO_DECIMAL.contains(upperCaseCode) || WHOLE_UNITS_IN_HUNDREDTHS.contains(upperCaseCode)
                ? 0
                : DEFAULT_ACCEPTED_SCALE;
    }
}
