package com.flowwallet.payment.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Bounds on a single deposit. Payment Service is the only service that enforces them, and the wallet does not
 * re-declare them. See docs/adr/0013-deposit-initiation.md.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "payment.deposit")
public class PaymentDepositProperties {
    /**
     * Smallest accepted amount, in major currency units, inclusive. The default is built from a string so that
     * the rejection message, which renders it, reads "1.00" and not "1.0".
     */
    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "payment.deposit.min-amount must be greater than zero")
    private BigDecimal minAmount = new BigDecimal("1.00");

    /**
     * Largest accepted amount, in major currency units, inclusive. {@code @Digits} keeps it within the
     * {@code NUMERIC(19,4)} column, so an amount the range accepts cannot overflow on insert.
     * See docs/adr/0015-currency-precision-and-no-rounding.md.
     */
    @NotNull
    @Digits(integer = 15, fraction = 4, message = "payment.deposit.max-amount must fit NUMERIC(19,4)")
    @DecimalMin(value = "0", inclusive = false, message = "payment.deposit.max-amount must be greater than zero")
    private BigDecimal maxAmount = new BigDecimal("10000.00");

    @AssertTrue(message = "payment.deposit.min-amount must not exceed payment.deposit.max-amount")
    public boolean isRangeOrdered() {
        return minAmount == null || maxAmount == null || minAmount.compareTo(maxAmount) <= 0;
    }
}
