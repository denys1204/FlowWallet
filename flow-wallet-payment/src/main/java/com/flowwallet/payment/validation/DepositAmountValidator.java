package com.flowwallet.payment.validation;

import com.flowwallet.payment.config.PaymentDepositProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * Spring builds constraint validators through {@code SpringConstraintValidatorFactory}, so this one can
 * take its bounds by constructor injection like any other bean.
 */
@RequiredArgsConstructor
public class DepositAmountValidator implements ConstraintValidator<DepositAmount, BigDecimal> {
    private final PaymentDepositProperties limits;

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.compareTo(limits.getMinAmount()) < 0) {
            return reject(context, "Minimum deposit amount is " + limits.getMinAmount().toPlainString());
        }
        if (value.compareTo(limits.getMaxAmount()) > 0) {
            return reject(context, "Maximum deposit amount is " + limits.getMaxAmount().toPlainString());
        }
        return true;
    }

    /**
     * Reports which bound was crossed and what it currently is, so the caller does not have to guess.
     * The text is safe to pass as a template: {@code toPlainString} emits only digits, a sign and a
     * decimal point, never the braces or dollar signs the message interpolator would try to resolve.
     */
    private boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
