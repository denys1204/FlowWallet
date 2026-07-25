package com.flowwallet.payment.transaction;

import com.flowwallet.common.security.CurrentUserIdResolver;
import com.flowwallet.common.web.GlobalExceptionHandler;
import com.flowwallet.payment.provider.exception.UnsupportedPaymentProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
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
                .build();
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
                { "transactionReference": "", "amount": 0, "currency": "US", "walletId": null, "providerName": "" }
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
        when(paymentService.initiatePayment(any(), anyString()))
                .thenThrow(new UnsupportedPaymentProviderException("Unsupported payment provider: FOO"));

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
