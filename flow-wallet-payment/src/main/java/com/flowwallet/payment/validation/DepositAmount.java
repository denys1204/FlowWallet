package com.flowwallet.payment.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Checks an amount against the configured deposit range.
 * <p>
 * This exists instead of {@code @DecimalMin}/{@code @DecimalMax} because annotation attributes must be
 * compile-time constants, so a bound that lives in configuration cannot be expressed as one. The
 * validator reads the range from {@code PaymentDepositProperties} at validation time, which is also why
 * it must stay in this module — the properties it depends on live here.
 * <p>
 * Presence is not its business: {@code null} passes, leaving that to {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = DepositAmountValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface DepositAmount {
    String message() default "is outside the accepted deposit range";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
