package com.flowwallet.wallet.deposit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Where Payment Service lives and how long the wallet waits for it.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "wallet.payment")
public class WalletPaymentProperties {
    /**
     * Payment Service's own address, not the gateway's, which has no route to the intent endpoint.
     * See docs/adr/0013-deposit-initiation.md.
     */
    @NotBlank
    private String baseUrl = "http://localhost:8082";

    /**
     * A zero timeout would fail every deposit at once with a 502 while health stays green, so startup refuses it.
     */
    @NotNull
    @DurationMin(millis = 1, message = "wallet.payment.connect-timeout must be at least 1ms")
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Long enough for Payment Service's own call to the provider, whose budget ({@code STRIPE_API_*}) is set to
     * end first. A wedged service accepts the connection and never answers, so only this bound ends the wait.
     * See docs/adr/0024-deposit-initiation-settles-its-own-races.md.
     */
    @NotNull
    @DurationMin(millis = 1, message = "wallet.payment.read-timeout must be at least 1ms")
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * The provider to charge through, configured here rather than chosen by the client while
     * {@code PaymentProvider} has one constant. The pattern lists that enum's constants, so a typo fails startup
     * instead of making Payment Service refuse every deposit with a 400 the wallet relays to its callers.
     * See docs/adr/0013-deposit-initiation.md.
     */
    @NotBlank
    @Pattern(regexp = "STRIPE", message = "wallet.payment.provider-name must be STRIPE")
    private String providerName = "STRIPE";
}
