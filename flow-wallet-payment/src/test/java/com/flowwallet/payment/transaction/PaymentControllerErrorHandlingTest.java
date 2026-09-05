package com.flowwallet.payment.transaction;

import com.flowwallet.payment.config.PaymentDepositProperties;
import com.flowwallet.payment.validation.DepositAmountValidator;
import com.flowwallet.platform.security.CurrentUserIdResolver;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the shared {@link GlobalExceptionHandler} and the {@code @CurrentUserId} resolver are
 * wired end-to-end through {@link PaymentController}, producing the correct status + RFC 9457
 * {@code application/problem+json} body. Uses a standalone MockMvc setup (no full application context)
 * so the test is fast and focused.
 */
class PaymentControllerErrorHandlingTest {
    private final PaymentService paymentService = Mockito.mock(PaymentService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdResolver())
                .setValidator(validatorWithDefaultLimits())
                .build();
    }

    /**
     * standaloneSetup builds its own validator and instantiates constraint validators reflectively, so
     * DepositAmountValidator — which takes its bounds by constructor injection — would not survive that.
     * Handing it a factory that knows how to build it keeps this a plain unit test instead of forcing a
     * Spring context on the whole class, and pins the defaults the assertions below rely on.
     */
    private static Validator validatorWithDefaultLimits() {
        PaymentDepositProperties limits = new PaymentDepositProperties();
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.setConstraintValidatorFactory(new ConstraintValidatorFactory() {
            @Override
            public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                return key == DepositAmountValidator.class
                        ? key.cast(new DepositAmountValidator(limits))
                        : BeanUtils.instantiateClass(key);
            }

            @Override
            public void releaseInstance(ConstraintValidator<?, ?> instance) {
                // nothing to release
            }
        });
        factory.afterPropertiesSet();
        return factory;
    }

    private static final String VALID_BODY = """
            {
              "transactionReference": "ref-123",
              "amount": 50.00,
              "currency": "USD",
              "walletId": 1,
              "providerName": "STRIPE"
            }
            """;

    @Test
    void missingUserIdHeaderReturns401() throws Exception {
        mockMvc.perform(post("/api/payments/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/payments/intent"));
    }

    @Test
    void invalidRequestBodyReturns400WithFieldErrors() throws Exception {
        String invalidBody = """
                {
                    "transactionReference": "",
                    "amount": 0,
                    "currency": "US",
                    "walletId": null,
                    "providerName": ""
                }
                """;

        mockMvc.perform(post("/api/payments/intent")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unsupportedProviderReturns400AsProblemJson() throws Exception {
        when(paymentService.initiatePayment(any(), anyString())).thenThrow(
                new UnsupportedPaymentProviderException("Unsupported payment provider: FOO")
        );

        mockMvc.perform(post("/api/payments/intent")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Unsupported payment provider: FOO"));
    }
}
