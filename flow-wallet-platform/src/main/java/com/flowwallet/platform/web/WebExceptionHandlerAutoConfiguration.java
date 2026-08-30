package com.flowwallet.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers the shared {@link GlobalExceptionHandler} in any servlet-based service that depends on
 * {@code flow-wallet-common}. Activated via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * <p>
 * Does NOT activate for reactive/WebFlux applications (e.g. the API Gateway). A service can override
 * the handler by declaring its own {@link GlobalExceptionHandler} bean.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebExceptionHandlerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler flowWalletGlobalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
