package com.flowwallet.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Auto-configuration that registers {@link CurrentUserIdResolver}
 * in any Spring Boot servlet-based web application that depends on {@code flow-wallet-common}.
 * <p>
 * Activated automatically via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Does NOT activate for WebFlux applications (e.g. API Gateway).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CurrentUserIdAutoConfiguration implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdResolver());
    }
}
