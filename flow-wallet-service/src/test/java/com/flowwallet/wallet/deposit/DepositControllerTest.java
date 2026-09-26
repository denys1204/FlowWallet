package com.flowwallet.wallet.deposit;

import com.flowwallet.platform.security.CurrentUserIdResolver;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The idempotency key rules are enforced by an annotation on a controller parameter, so they only show up
 * when a request actually passes through Spring MVC's validation.
 */
class DepositControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";

    private final DepositService deposits = mock(DepositService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // The controller is @Validated, and with class-level @Validated Spring validates parameters through
        // an AOP proxy and deliberately switches its built-in MVC method validation off. standaloneSetup
        // creates no proxy, so without this one the header constraint would simply never run here -- while
        // running fine in the real application. Wrapping the controller the same way keeps the test honest.
        ProxyFactory proxy = new ProxyFactory(new DepositController(deposits));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new MethodValidationInterceptor((jakarta.validation.Validator) validator));

        mockMvc = MockMvcBuilders.standaloneSetup(proxy.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdResolver())
                .setValidator(validator)
                .build();

        when(deposits.start(anyString(), anyString(), anyString(), any())).thenReturn(
                new DepositResponse("ref", "STRIPE", Map.of("clientSecret", "cs"))
        );
    }

    private int deposit(String key) throws Exception {
        return mockMvc.perform(post("/api/wallets/USD/deposits")
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25.00}"))
                .andReturn().getResponse().getStatus();
    }

    @ParameterizedTest(name = "{1} key is accepted")
    @CsvSource({
            "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d, version 4",
            "018f3a2b-7c4d-7e5f-8a9b-0c1d2e3f4a5b, version 7 (the default of client libraries such as uuid7)",
            "886313e1-3b8a-5372-9b90-0c9aee199e5d, version 5 (a stable key derived from an order number)",
            "4C9A1B2E-1F3D-4A5B-8C7D-9E0F1A2B3C4D, upper-case",
    })
    void acceptsAnyUuidVersion(String key, String description) throws Exception {
        // The annotation's default is versions 1 to 5, so without the explicit list a version-7 key was
        // refused while the Javadoc promised any version. The key only has to be unique, not unguessable.
        Assertions.assertThat(deposit(key)).isEqualTo(200);
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @CsvSource({
            "00000000-0000-0000-0000-000000000000",
            "1-1-1-1-1",
            "not-a-uuid",
    })
    void refusesAnythingThatIsNotAUsableUuid(String key) throws Exception {
        Assertions.assertThat(deposit(key)).isEqualTo(400);
        verify(deposits, never()).start(anyString(), anyString(), anyString(), any());
    }
}
