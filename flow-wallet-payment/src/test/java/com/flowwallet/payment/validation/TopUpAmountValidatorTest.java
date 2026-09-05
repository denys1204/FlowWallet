package com.flowwallet.payment.validation;

import com.flowwallet.payment.config.PaymentTopUpProperties;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

class TopUpAmountValidatorTest {
    private ConstraintValidatorContext.ConstraintViolationBuilder builder;
    private ConstraintValidatorContext context;
    private TopUpAmountValidator validator;

    @BeforeEach
    void setUp() {
        PaymentTopUpProperties limits = new PaymentTopUpProperties();
        limits.setMinAmount(new BigDecimal("5.00"));
        limits.setMaxAmount(new BigDecimal("100.00"));
        validator = new TopUpAmountValidator(limits);

        context = mock(ConstraintValidatorContext.class);
        builder = mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
        when(context.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
    }

    @Test
    @DisplayName("accepts an amount inside the configured range")
    void acceptsInRange() {
        assertThat(validator.isValid(new BigDecimal("50.00"), context)).isTrue();
    }

    @Test
    @DisplayName("both bounds are inclusive, as the annotations they replaced were")
    void boundsAreInclusive() {
        assertThat(validator.isValid(new BigDecimal("5.00"), context)).isTrue();
        assertThat(validator.isValid(new BigDecimal("100.00"), context)).isTrue();
    }

    @Test
    @DisplayName("rejects below the configured minimum and says what it is")
    void rejectsBelowMinimum() {
        assertThat(validator.isValid(new BigDecimal("4.99"), context)).isFalse();

        var message = forClass(String.class);
        verify(context).buildConstraintViolationWithTemplate(message.capture());
        assertThat(message.getValue()).isEqualTo("Minimum top-up amount is 5.00");
    }

    @Test
    @DisplayName("rejects above the configured maximum and says what it is")
    void rejectsAboveMaximum() {
        assertThat(validator.isValid(new BigDecimal("100.01"), context)).isFalse();

        var message = forClass(String.class);
        verify(context).buildConstraintViolationWithTemplate(message.capture());
        assertThat(message.getValue()).isEqualTo("Maximum top-up amount is 100.00");
    }

    @Test
    @DisplayName("the message reports the configured bound, not the shipped default")
    void messageFollowsConfiguration() {
        PaymentTopUpProperties raised = new PaymentTopUpProperties();
        raised.setMaxAmount(new BigDecimal("250000.00"));
        var withRaisedCeiling = new TopUpAmountValidator(raised);

        assertThat(withRaisedCeiling.isValid(new BigDecimal("250000.01"), context)).isFalse();

        var message = forClass(String.class);
        verify(context).buildConstraintViolationWithTemplate(message.capture());
        assertThat(message.getValue()).isEqualTo("Maximum top-up amount is 250000.00");
    }

    @Test
    @DisplayName("passes null so that @NotNull keeps sole ownership of presence")
    void passesNull() {
        assertThat(validator.isValid(null, context)).isTrue();
    }
}
