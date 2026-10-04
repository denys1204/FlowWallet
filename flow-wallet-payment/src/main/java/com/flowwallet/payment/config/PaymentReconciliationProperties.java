package com.flowwallet.payment.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the reconciler re-checks deposits whose webhook may have been lost.
 * See docs/adr/0032-pending-payments-are-rechecked-with-the-provider.md.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "payment.reconciliation")
public class PaymentReconciliationProperties {
    /**
     * Whether the reconciler runs at all.
     */
    private boolean enabled = true;

    /**
     * Pause between two runs, in ms.
     */
    @Min(value = 1000, message = "payment.reconciliation.interval-ms must be at least 1000")
    private long intervalMs = 300000;

    /**
     * How old a payment must be before the provider is asked about it, and how long the reconciler waits before
     * asking about the same payment again. Below it the webhook is still expected.
     */
    @NotNull
    @DurationMin(seconds = 1, message = "payment.reconciliation.min-age must be at least 1s")
    private Duration minAge = Duration.ofMinutes(15);

    /**
     * How old a payment may be and still be asked about. An older one is taken as an abandoned checkout.
     */
    @NotNull
    @DurationMin(seconds = 1, message = "payment.reconciliation.max-age must be at least 1s")
    private Duration maxAge = Duration.ofHours(24);

    /**
     * How many payments one run asks about. Each is one sequential provider call.
     */
    @Min(value = 1, message = "payment.reconciliation.batch-size must be at least 1")
    private int batchSize = 50;

    @AssertTrue(message = "payment.reconciliation.min-age must be shorter than payment.reconciliation.max-age")
    public boolean isAgeWindowOrdered() {
        return minAge == null || maxAge == null || minAge.compareTo(maxAge) < 0;
    }
}
