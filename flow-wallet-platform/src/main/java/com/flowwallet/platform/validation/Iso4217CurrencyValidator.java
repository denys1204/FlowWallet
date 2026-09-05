package com.flowwallet.platform.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Currency;

/**
 * Validates against the JDK's own ISO 4217 table, so the list stays current with the runtime instead
 * of drifting in a hand-maintained constant.
 */
public class Iso4217CurrencyValidator implements ConstraintValidator<Iso4217Currency, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            Currency.getInstance(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
