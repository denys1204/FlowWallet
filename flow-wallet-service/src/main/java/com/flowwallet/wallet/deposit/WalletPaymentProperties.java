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
     * Payment Service's own address, not the gateway's. Service-to-service traffic has no reason to leave
     * through the front door, and routing it there would make an internal call depend on the edge being up.
     */
    @NotBlank
    private String baseUrl = "http://localhost:8082";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Long enough for a provider round-trip, short enough that a wedged Payment Service does not hold a
     * wallet request thread indefinitely. A wedged service is worse than a dead one: it accepts the
     * connection and never answers, so only this bound ends the wait.
     */
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * The provider to charge through. Not a field on the request: {@code PaymentProvider} has exactly one
     * constant, so asking the client to choose would be a required field with one legal value it can only
     * get wrong. It becomes a request field the day a second provider exists, which is additive.
     */
    @NotBlank
    private String providerName = "STRIPE";
}
