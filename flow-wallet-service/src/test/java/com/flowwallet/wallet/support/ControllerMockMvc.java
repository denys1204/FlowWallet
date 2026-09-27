package com.flowwallet.wallet.support;

import com.flowwallet.platform.security.CurrentUserIdResolver;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import jakarta.validation.Validator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

/**
 * Standalone {@link MockMvc} for one wallet controller, wired the way the application runs it: the platform's
 * {@link GlobalExceptionHandler} and {@link CurrentUserIdResolver}, and bean validation of bodies and parameters.
 * <p>
 * The controllers are {@code @Validated}, and with class-level {@code @Validated} Spring validates path, query and
 * header parameters through an AOP proxy and switches its built-in MVC method validation off. Standalone MockMvc
 * creates no proxy, so without the one built here those constraints would never run in a test while running in
 * the application.
 */
public final class ControllerMockMvc {
    private ControllerMockMvc() {
    }

    public static MockMvc of(Object controller) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        ProxyFactory proxy = new ProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new MethodValidationInterceptor((Validator) validator));

        return MockMvcBuilders.standaloneSetup(proxy.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdResolver())
                .setValidator(validator)
                .build();
    }
}
