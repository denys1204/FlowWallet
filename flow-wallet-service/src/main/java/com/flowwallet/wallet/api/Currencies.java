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
     * Upper-cases, then validates. {@code Currency.getInstance} is case-sensitive, and the
     * {@code wallets_currency_is_upper} CHECK stores only upper case, so {@code usd} must reach the {@code USD}
     * wallet. See docs/adr/0015-currency-precision-and-no-rounding.md.
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
