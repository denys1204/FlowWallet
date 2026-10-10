package com.flowwallet.payment.provider.stripe;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The smallest payout Stripe makes, the rows for the countries below from the "Minimum payout amounts per country"
 * table on https://docs.stripe.com/payouts, retrieved on 2026-10-10. Stripe lists one minimum per account country, in
 * that country's own currency, and documents none for an account paying out in another currency; a country or
 * currency missing here is not checked. Copied by hand, like {@link StripeChargeLimits}.
 * See docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md.
 */
public final class StripePayoutMinimums {
    private static final Map<String, Minimum> BY_COUNTRY = byCountry();

    private StripePayoutMinimums() {
    }

    /**
     * @return Stripe's minimum payout for an account in the country paying out in the currency, or empty where
     *         Stripe's table gives none
     */
    public static Optional<BigDecimal> of(String country, String currency) {
        Minimum minimum = BY_COUNTRY.get(country.toUpperCase(Locale.ROOT));
        return minimum != null && minimum.currency().equalsIgnoreCase(currency)
                ? Optional.of(minimum.amount())
                : Optional.empty();
    }

    private static Map<String, Minimum> byCountry() {
        Map<String, Minimum> table = new HashMap<>(Map.ofEntries(
                Map.entry("PL", new Minimum("PLN", "5")),
                Map.entry("CZ", new Minimum("CZK", "30")),
                Map.entry("HU", new Minimum("HUF", "360")),
                Map.entry("SE", new Minimum("SEK", "20")),
                Map.entry("DK", new Minimum("DKK", "20")),
                Map.entry("NO", new Minimum("NOK", "20")),
                Map.entry("CH", new Minimum("CHF", "5")),
                Map.entry("LI", new Minimum("CHF", "5")),
                Map.entry("RO", new Minimum("RON", "5")),
                Map.entry("US", new Minimum("USD", "0.01")),
                Map.entry("GB", new Minimum("GBP", "1")),
                Map.entry("GI", new Minimum("GBP", "1"))
        ));
        for (String euroCountry : new String[]{
                "AT", "BE", "BG", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE",
                "IT", "LV", "LT", "LU", "MT", "NL", "PT", "SK", "SI", "ES"
        }) {
            table.put(euroCountry, new Minimum("EUR", "1"));
        }
        return Map.copyOf(table);
    }

    private record Minimum(String currency, BigDecimal amount) {
        Minimum(String currency, String amount) {
            this(currency, new BigDecimal(amount));
        }
    }
}
