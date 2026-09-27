package com.flowwallet.payment.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on the public webhook route. See docs/adr/0017-webhooks-verified-before-they-are-read.md.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "payment.webhook")
public class PaymentWebhookProperties {
    /**
     * The whole body is held in memory while its signature is checked, so the ceiling keeps it an int-sized array
     * with room to spare.
     */
    private static final DataSize CEILING = DataSize.ofMegabytes(16);

    /**
     * Largest webhook body accepted, checked against {@code Content-Length} and while the body is read. A larger one
     * is answered with 413 before its signature is checked.
     */
    @NotNull
    private DataSize maxPayloadSize = DataSize.ofKilobytes(256);

    @AssertTrue(message = "payment.webhook.max-payload-size must be between 1B and 16MB")
    public boolean isMaxPayloadSizeInRange() {
        return maxPayloadSize == null
                || (maxPayloadSize.toBytes() > 0 && maxPayloadSize.compareTo(CEILING) <= 0);
    }
}
