package com.flowwallet.payment.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Checks an amount against the deposit range configured in {@code PaymentDepositProperties}, read at validation
 * time. {@code null} passes, leaving presence to {@code @NotNull}. See docs/adr/0013-deposit-initiation.md.
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
