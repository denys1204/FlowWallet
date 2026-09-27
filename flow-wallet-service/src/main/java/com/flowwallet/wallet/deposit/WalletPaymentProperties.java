package com.flowwallet.wallet.deposit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
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

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Long enough for Payment Service's own call to the provider. A wedged service accepts the connection and
     * never answers, so only this bound ends the wait.
     */
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * The provider to charge through, configured here rather than chosen by the client while
     * {@code PaymentProvider} has one constant. See docs/adr/0013-deposit-initiation.md.
     */
    @NotBlank
    private String providerName = "STRIPE";
}
