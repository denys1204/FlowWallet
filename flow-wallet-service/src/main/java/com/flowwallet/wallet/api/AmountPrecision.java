package com.flowwallet.wallet.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * The grid an amount moved inside the wallet must sit on, and the single form it is stored and compared in.
 * <p>
 * The grid is a copy of the accepted scale in Payment Service's {@code StripeCurrencyRules}: no decimals for
 * its sixteen zero-decimal currencies, two for every other. Every unit in a wallet arrived through a deposit on
 * that grid, and a payout would leave on it, so a transfer that created finer units would leave balances that
 * can never be paid out in full. The copy is deliberate. Services never depend on each other, the platform
 * module holds nothing domain-shaped, and the contract module holds only what crosses the wire. The two lists
 * reference each other, and a change to one must be made to both.
 * <p>
 * {@code java.util.Currency} is never consulted. ISO's minor units are wrong in both directions here: they give
 * MGA two decimals no deposit can produce, and give ISK none, so a deposited 10.50 could never move its 0.50.
 * <p>
 * The check is code rather than {@code @Digits} on the request. Hibernate Validator counts a
 * {@code BigDecimal}'s own scale, trailing zeros included, so {@code @Digits(fraction = 2)} would refuse a
 * correctly written 25.100. An annotation also cannot see the currency, which only the path carries.
 * <p>
 * An amount off the grid is refused, never rounded. Rounding would move a sum the caller did not ask for, and
 * Postgres itself rounds a value finer than the column's four decimals on insert, so 0.00001 would be stored
 * as a movement of nothing.
 */
public final class AmountPrecision {
    /**
     * The integer room in {@code NUMERIC(19,4)}. No balance can hold a larger amount, so none could cover
     * one, and without this bound the caller would hear "Insufficient funds" (a 422) about an amount that is
     * wrong in itself. Refused here, it gets a 400 that names the problem, before {@code setScale} ever sees
     * it.
     */
    private static final int MAX_INTEGER_DIGITS = 15;

    /**
     * The ledger's scale, so the amount a response prints matches the one a replay reads back.
     */
    private static final int LEDGER_SCALE = 4;

    private static final int DEFAULT_ACCEPTED_SCALE = 2;

    /**
     * Copied from {@code StripeCurrencyRules.ZERO_DECIMAL}, which points back here. Every other currency
     * accepts two decimals there, the three-decimal ones included.
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
     * Both checks judge the amount without its trailing zeros, so 10.5000 USD and 1E+2 JPY are as good as
     * 10.5 and 100. The size check comes first, so an amount such as 1E+999999999 is refused before
     * {@code setScale} could try to write out a billion digits. It subtracts in {@code long}, because a scale
     * near the {@code int} limit would overflow the subtraction and let the largest amounts through. After
     * both checks the rescale is exact, and {@code RoundingMode.UNNECESSARY} says so: it would throw rather
     * than round if a later change broke that.
     *
     * @param amount   a positive amount in major units; the sign is the request's concern
     * @param currency the ISO code of the wallet the amount moves in
     * @return the same value at scale 4, the one form it is stored, compared and returned in
     * @throws InvalidAmountException if the amount has more than 15 integer digits or more decimal places than
     *                                the currency accepts
     */
    public static BigDecimal canonical(BigDecimal amount, String currency) {
        String code = currency.toUpperCase(Locale.ROOT);
        BigDecimal stripped = amount.stripTrailingZeros();

        if ((long) stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            throw InvalidAmountException.tooLarge();
        }
        int acceptedScale = ZERO_DECIMAL.contains(code) ? 0 : DEFAULT_ACCEPTED_SCALE;
        if (stripped.scale() > acceptedScale) {
            throw InvalidAmountException.tooPrecise(code, acceptedScale);
        }
        return stripped.setScale(LEDGER_SCALE, RoundingMode.UNNECESSARY);
    }
}
