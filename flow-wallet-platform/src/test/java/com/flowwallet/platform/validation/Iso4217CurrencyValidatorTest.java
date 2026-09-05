package com.flowwallet.platform.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class Iso4217CurrencyValidatorTest {
    private final Iso4217CurrencyValidator validator = new Iso4217CurrencyValidator();

    @ParameterizedTest
    @DisplayName("accepts real currency codes")
    @ValueSource(strings = {"USD", "EUR", "PLN", "UAH", "JPY"})
    void acceptsRealCodes(String code) {
        assertThat(validator.isValid(code, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC", "US", "USDD", "123", "", " "})
    @DisplayName("rejects what a length check would have let through")
    void rejectsInvalidCodes(String code) {
        assertThat(validator.isValid(code, null)).isFalse();
    }

    @Test
    @DisplayName("rejects lowercase — ISO 4217 codes are uppercase")
    void rejectsLowercase() {
        assertThat(validator.isValid("usd", null)).isFalse();
    }

    @Test
    @DisplayName("passes null so that presence stays the concern of @NotBlank")
    void passesNull() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    @DisplayName("accepts XXX, which the standard defines as the absence of a currency")
    void acceptsTheNoCurrencyCode() {
        assertThat(validator.isValid("XXX", null)).isTrue();
    }
}
