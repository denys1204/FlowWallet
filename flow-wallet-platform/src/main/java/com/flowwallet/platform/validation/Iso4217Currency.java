package com.flowwallet.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Accepts only currency codes the JDK recognises as ISO 4217.
 * <p>
 * A three-character length check lets {@code "ABC"} through, and a bad currency is not the kind of
 * mistake that should surface later as a mismatch between a payment and a wallet.
 * <p>
 * Presence is deliberately not this annotation's business: {@code null} passes, so pair it with
 * {@code @NotBlank} where the value is required.
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
