package com.flowwallet.wallet.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * The grid an amount moved inside the wallet must sit on, and the single form it is stored and compared in.
 * <p>
 * The grid copies the accepted scale in Payment Service's {@code StripeCurrencyRules}: whole units for its
 * zero-decimal currencies, two decimals for every other. An amount off the grid is refused, never rounded.
 * See docs/adr/0015-currency-precision-and-no-rounding.md.
 */
public final class AmountPrecision {
    /**
     * The integer room in {@code NUMERIC(19,4)}. No balance can hold a larger amount, so it gets a 400 here
     * instead of an "Insufficient funds" 422.
     */
    private static final int MAX_INTEGER_DIGITS = 15;

    /**
     * The ledger's scale, so the amount a response prints matches the one a replay reads back.
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
     * @param currency the ISO code of the wallet the amount moves in
     * @return the same value at scale 4
     * @throws InvalidAmountException if the amount has more than 15 integer digits or more decimal places than
     *                                the currency accepts
     */
    public static BigDecimal canonical(BigDecimal amount, String currency) {
        String code = currency.toUpperCase(Locale.ROOT);
        BigDecimal stripped = amount.stripTrailingZeros();

        if (!fitsIntegerDigits(stripped)) {
            throw InvalidAmountException.tooLarge();
        }
        int acceptedScale = acceptedScale(code);
        if (stripped.scale() > acceptedScale) {
            throw InvalidAmountException.tooPrecise(code, acceptedScale);
        }
        return stripped.setScale(LEDGER_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Whether {@link #canonical} would accept the amount, for a caller that records a refusal instead of throwing
     * one, such as the payment event consumer. The sign is not judged.
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

    private static boolean fitsIntegerDigits(BigDecimal stripped) {
        return (long) stripped.precision() - stripped.scale() <= MAX_INTEGER_DIGITS;
    }

    private static int acceptedScale(String upperCaseCode) {
        return ZERO_DECIMAL.contains(upperCaseCode) ? 0 : DEFAULT_ACCEPTED_SCALE;
    }
}
