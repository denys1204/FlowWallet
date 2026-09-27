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

    /**
     * {@link #normalise}, then refuses a code that ISO 4217 gives no minor unit: the precious metals (XAU, XAG,
     * XPT, XPD), the SDR and the bond-market units (XDR, XBA to XBD), XSU, XUA, the withdrawn XFO and XFU, the
     * testing code XTS and XXX for "no currency". None is a means of payment, so no deposit could fund the wallet.
     * The JDK marks them with a default fraction digit count of -1, which keeps the check free of any payment
     * provider's list.
     * See docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md.
     */
    public static String normaliseForNewWallet(String currency) {
        String code = normalise(currency);
        if (Currency.getInstance(code).getDefaultFractionDigits() < 0) {
            throw new NonPaymentCurrencyException(code);
        }
        return code;
    }
}
