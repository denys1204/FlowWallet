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
 * Bounds on a single deposit. Payment Service owns this rule and is the only place that enforces it,
 * so a caller forwarding a request here does not re-declare the range and the two cannot disagree.
 * <p>
 * These are commercial limits, not properties of the domain — a risk team tightens them, a regulator
 * moves them, a sandbox wants a smaller floor — so they belong in configuration rather than in a
 * constant that requires a rebuild.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "payment.deposit")
public class PaymentDepositProperties {
    /**
     * Smallest accepted amount, in major currency units, inclusive.
     * <p>
     * Declared with {@code new BigDecimal("1.00")} rather than a double literal so the scale survives:
     * the rejection message renders this value, and {@code 1.0} would read back as "1.0".
     */
    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "payment.deposit.min-amount must be greater than zero")
    private BigDecimal minAmount = new BigDecimal("1.00");

    /**
     * Largest accepted amount, in major currency units, inclusive.
     * <p>
     * Bounded above as well as below because the column behind it is {@code NUMERIC(19,4)}: a limit raised
     * past fifteen integer digits would let a request pass validation and then fail on insert as a numeric
     * overflow, which reaches the caller as a 500 for a value the service said it would accept. Startup is
     * the right place to refuse that.
     */
    @NotNull
    @Digits(integer = 15, fraction = 4, message = "payment.deposit.max-amount must fit NUMERIC(19,4)")
    @DecimalMin(value = "0", inclusive = false, message = "payment.deposit.max-amount must be greater than zero")
    private BigDecimal maxAmount = new BigDecimal("10000.00");

    /**
     * An inverted range would reject every payment while the service still reported itself healthy —
     * the worst kind of misconfiguration, because nothing points at the cause. Failing startup instead.
     */
    @AssertTrue(message = "payment.deposit.min-amount must not exceed payment.deposit.max-amount")
    public boolean isRangeOrdered() {
        return minAmount == null || maxAmount == null || minAmount.compareTo(maxAmount) <= 0;
    }
}
