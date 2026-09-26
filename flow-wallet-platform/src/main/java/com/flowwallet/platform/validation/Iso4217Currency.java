package com.flowwallet.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Accepts only currency codes the JDK recognises as ISO 4217. The check is case-sensitive, so {@code "usd"} fails.
 * {@code null} passes: pair it with {@code @NotBlank} where the value is required.
 * See docs/adr/0015-currency-precision-and-no-rounding.md.
 */
@Documented
@Retention(RUNTIME)
@Constraint(validatedBy = Iso4217CurrencyValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
public @interface Iso4217Currency {
    String message() default "must be a valid ISO 4217 currency code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
