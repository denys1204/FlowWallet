package com.flowwallet.payment.provider;

import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentProviderFactoryTest {
    private static final Locale DEFAULT_LOCALE = Locale.getDefault();

    private final PaymentProviderStrategy stripe = mock(PaymentProviderStrategy.class);
    private final PaymentProviderFactory factory = new PaymentProviderFactory(List.of(stripe));

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(DEFAULT_LOCALE);
    }

    @Test
    void aLowerCaseProviderNameResolvesUnderATurkishDefaultLocale() {
        // Guards upper-casing with the host's locale: under tr_TR "stripe".toUpperCase() is "STRİPE", with a
        // dotted capital I, so every deposit and webhook on such a host would be refused as an unknown provider.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertThat(factory.resolve("stripe")).isEqualTo(PaymentProvider.STRIPE);
    }

    @Test
    void theStrategyIsFoundByTheResolvedProvider() {
        // Guards the lookup by name and the lookup by constant drifting apart for the webhook path.
        when(stripe.supports(PaymentProvider.STRIPE)).thenReturn(true);

        assertThat(factory.getStrategy("stripe")).isSameAs(stripe);
    }

    @Test
    void anUnknownProviderIsRefusedAsABadRequest() {
        // Guards an IllegalArgumentException from Enum.valueOf escaping as a 500.
        assertThatThrownBy(() -> factory.resolve("paypal"))
                .isInstanceOf(UnsupportedPaymentProviderException.class)
                .hasMessage("Unsupported payment provider");
    }

    @Test
    void theRefusalDoesNotQuoteTheProviderName() {
        // Guards log forging through the public webhook path: the handler logs every 4xx detail, so a quoted
        // name with a line break would write a line of the caller's choosing, and a long one would be copied
        // into the log and the response.
        assertThatThrownBy(() -> factory.resolve("paypal\nERROR forged line"))
                .isInstanceOf(UnsupportedPaymentProviderException.class)
                .hasMessage("Unsupported payment provider");
    }
}
