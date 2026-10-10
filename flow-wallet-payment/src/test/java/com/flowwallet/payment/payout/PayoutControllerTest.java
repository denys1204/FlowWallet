package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutPreflightResponse;
import com.flowwallet.platform.security.CurrentUserIdResolver;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link PayoutController} through a standalone {@code MockMvc} with the platform's error handler and identity
 * resolver.
 */
class PayoutControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";
    private static final String PATH = "/api/payments/payouts/preflight";

    private final PayoutPreflight preflight = mock(PayoutPreflight.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PayoutController(preflight))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new CurrentUserIdResolver())
            .build();

    @Test
    void theCallersWithdrawalIsCheckedAndTheAnswerComesBack() throws Exception {
        // Guards the preflight answered for another caller, or without its two fields.
        when(preflight.check(eq(CALLER), any())).thenReturn(new PayoutPreflightResponse("READY", true));

        mockMvc.perform(post(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"PLN\", \"amount\": 75.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccount").value("READY"))
                .andExpect(jsonPath("$.reserveSufficient").value(true));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"currency\": \"pln\", \"amount\": 75.00}",
            "{\"amount\": 75.00}",
            "{\"currency\": \"PLN\"}"
    })
    void aLowerCaseOrMissingCurrencyOrAMissingAmountIsA400(String body) throws Exception {
        // Guards a currency the terms would compare in another case than the configured list, and a missing field
        // reaching the service as a null it would fail on with a 500.
        mockMvc.perform(post(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(preflight);
    }

    @Test
    void aCallerWithoutAnIdentityIsA401BeforeTheBodyIsChecked() throws Exception {
        // Guards the parameter order: with @CurrentUserId after the body, the wallet would be told to fix the body.
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(preflight);
    }
}
