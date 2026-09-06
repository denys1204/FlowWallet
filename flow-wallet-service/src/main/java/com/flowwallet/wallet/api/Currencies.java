package com.flowwallet.wallet.api;

import java.util.Currency;
import java.util.Locale;

/**
 * Turns whatever the caller wrote into the code the database stores.
 */
public final class Currencies {
    private Currencies() {
    }

    /**
     * Upper-cases, then validates — in that order, and deliberately.
     * <p>
     * {@code Currency.getInstance} is case-sensitive, so validating first would answer a casing mistake with
     * "not a valid ISO 4217 code", which is both wrong and unhelpful. Upper-casing also matches how the
     * value is stored: the schema carries a CHECK that the column equals its own upper-case, so without this
     * {@code /api/wallets/usd} would miss a wallet that plainly exists.
     */
    public static String normalise(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidCurrencyException(currency);
        }
        String code = currency.toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new InvalidCurrencyException(currency);
        }
        return code;
    }
}
