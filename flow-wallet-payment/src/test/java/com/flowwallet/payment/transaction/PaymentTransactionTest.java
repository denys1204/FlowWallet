package com.flowwallet.payment.transaction;

import com.flowwallet.payment.dto.CreatePaymentIntentRequest;
import com.flowwallet.payment.provider.PaymentProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTransactionTest {
    private static final Locale DEFAULT_LOCALE = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(DEFAULT_LOCALE);
    }

    @Test
    void theProviderIsStoredAsTheConstantsName() {
        // Guards storing the caller's spelling: a row written as "stripe" and one written as "STRIPE" would
        // then describe one provider two ways.
        PaymentTransaction transaction = PaymentTransaction.create(request("stripe"), "user-1", PaymentProvider.STRIPE);

        assertThat(transaction.getProviderName()).isEqualTo("STRIPE");
        assertThat(transaction.getCurrency()).isEqualTo("USD");
    }

    @Test
    void aRetryThatSpellsTheProviderInLowerCaseIsNoConflictUnderATurkishDefaultLocale() {
        // Guards comparing with a locale-sensitive upper-casing: under tr_TR "stripe" upper-cases to "STRİPE",
        // and a byte-identical retry would get a 409 for a provider it never changed.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        PaymentTransaction transaction = PaymentTransaction.create(request("STRIPE"), "user-1", PaymentProvider.STRIPE);

        assertThat(transaction.differencesFrom(request("stripe"))).isEmpty();
    }

    private static CreatePaymentIntentRequest request(String providerName) {
        return new CreatePaymentIntentRequest("ref-1", new BigDecimal("50.00"), "USD", providerName);
    }
}
