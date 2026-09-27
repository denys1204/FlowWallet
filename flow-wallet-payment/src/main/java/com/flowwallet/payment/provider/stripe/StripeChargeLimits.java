package com.flowwallet.payment.provider.stripe;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which currencies Stripe charges and the smallest charge it accepts in each. Both lists are a copy of Stripe's
 * currency page (https://docs.stripe.com/currencies), retrieved on 2026-09-27; the SDK exposes neither, so a change
 * at Stripe is copied here by hand, as for the tables in {@link StripeCurrencyRules}. A request these lists refuse
 * gets a 400 before its transaction row is reserved, so the reference stays free.
 * See docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md.
 */
public final class StripeChargeLimits {
    /**
     * Stripe's supported presentment currencies for card payments (134 codes, some available only to accounts in
     * certain countries), plus the five three-decimal currencies whose charges the same page documents and
     * {@link StripeCurrencyRules} converts. Every ISO code outside it, such as XAU or XTS, is refused.
     */
    private static final Set<String> CHARGEABLE = Set.of(
            "AED", "AFN", "ALL", "AMD", "ANG", "AOA", "ARS", "AUD", "AWG", "AZN", "BAM", "BBD", "BDT", "BIF",
            "BMD", "BND", "BOB", "BRL", "BSD", "BWP", "BYN", "BZD", "CAD", "CDF", "CHF", "CLP", "CNY", "COP",
            "CRC", "CVE", "CZK", "DJF", "DKK", "DOP", "DZD", "EGP", "ETB", "EUR", "FJD", "FKP", "GBP", "GEL",
            "GIP", "GMD", "GNF", "GTQ", "GYD", "HKD", "HNL", "HTG", "HUF", "IDR", "ILS", "INR", "ISK", "JMD",
            "JPY", "KES", "KGS", "KHR", "KMF", "KRW", "KYD", "KZT", "LAK", "LBP", "LKR", "LRD", "LSL", "MAD",
            "MDL", "MGA", "MKD", "MMK", "MNT", "MOP", "MUR", "MVR", "MWK", "MXN", "MYR", "MZN", "NAD", "NGN",
            "NIO", "NOK", "NPR", "NZD", "PAB", "PEN", "PGK", "PHP", "PKR", "PLN", "PYG", "QAR", "RON", "RSD",
            "RUB", "RWF", "SAR", "SBD", "SCR", "SEK", "SGD", "SHP", "SLE", "SOS", "SRD", "STD", "SZL", "THB",
            "TJS", "TOP", "TRY", "TTD", "TWD", "TZS", "UAH", "UGX", "USD", "UYU", "UZS", "VND", "VUV", "WST",
            "XAF", "XCD", "XCG", "XOF", "XPF", "YER", "ZAR", "ZMW",
            "BHD", "JOD", "KWD", "OMR", "TND"
    );

    /**
     * Stripe's minimum charge amounts, in major units, for the currencies it lists. For any other currency the
     * minimum is the settlement currency's after conversion, which only Stripe can judge, so a charge below it
     * reaches Stripe and comes back as a refusal.
     */
    private static final Map<String, BigDecimal> MINIMUMS = Map.ofEntries(
            Map.entry("AED", new BigDecimal("2.00")),
            Map.entry("ARS", new BigDecimal("0.50")),
            Map.entry("AUD", new BigDecimal("0.50")),
            Map.entry("BRL", new BigDecimal("0.50")),
            Map.entry("CAD", new BigDecimal("0.50")),
            Map.entry("CHF", new BigDecimal("0.50")),
            Map.entry("COP", new BigDecimal("0.50")),
            Map.entry("CZK", new BigDecimal("15.00")),
            Map.entry("DKK", new BigDecimal("2.50")),
            Map.entry("EUR", new BigDecimal("0.50")),
            Map.entry("GBP", new BigDecimal("0.30")),
            Map.entry("HKD", new BigDecimal("4.00")),
            Map.entry("HUF", new BigDecimal("175.00")),
            Map.entry("IDR", new BigDecimal("0.50")),
            Map.entry("ILS", new BigDecimal("0.50")),
            Map.entry("INR", new BigDecimal("0.50")),
            Map.entry("JPY", new BigDecimal("50")),
            Map.entry("KRW", new BigDecimal("50")),
            Map.entry("MXN", new BigDecimal("10")),
            Map.entry("MYR", new BigDecimal("2.00")),
            Map.entry("NOK", new BigDecimal("3.00")),
            Map.entry("NZD", new BigDecimal("0.50")),
            Map.entry("PHP", new BigDecimal("0.50")),
            Map.entry("PLN", new BigDecimal("2.00")),
            Map.entry("RON", new BigDecimal("2.00")),
            Map.entry("RUB", new BigDecimal("0.50")),
            Map.entry("SEK", new BigDecimal("3.00")),
            Map.entry("SGD", new BigDecimal("0.50")),
            Map.entry("THB", new BigDecimal("10")),
            Map.entry("USD", new BigDecimal("0.50")),
            Map.entry("ZAR", new BigDecimal("0.50"))
    );

    private StripeChargeLimits() {
    }

    public static boolean isChargeable(String currency) {
        return CHARGEABLE.contains(currency.toUpperCase(Locale.ROOT));
    }

    /**
     * @return Stripe's smallest charge in the currency, in major units, or empty where Stripe lists none
     */
    public static Optional<BigDecimal> minimumCharge(String currency) {
        return Optional.ofNullable(MINIMUMS.get(currency.toUpperCase(Locale.ROOT)));
    }
}
