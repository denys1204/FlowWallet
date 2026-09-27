package com.flowwallet.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link GlobalExceptionHandler} in every servlet service that depends on {@code flow-wallet-platform}.
 * Listed in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}. A service
 * replaces the handler by declaring its own {@link GlobalExceptionHandler} bean.
 * See docs/adr/0002-module-boundaries.md.
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
