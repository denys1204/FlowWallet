package com.flowwallet.payment.payout;

import com.flowwallet.payment.dto.PayoutAccountResponse;
import com.flowwallet.platform.security.CurrentUserIdResolver;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link PayoutAccountController} through a standalone {@code MockMvc} with the platform's error handler and
 * identity resolver, as {@code PaymentControllerErrorHandlingTest} does for the intent.
 */
class PayoutAccountControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";
    private static final String PATH = "/api/payments/payout-accounts";

    private final PayoutAccountService service = mock(PayoutAccountService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PayoutAccountController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new CurrentUserIdResolver())
            .build();

    @Test
    void aTokenWithTheTermsAcceptedSetsTheCallersBankAccount() throws Exception {
        // Guards the relay's happy path: the caller's own id and token reach the service, and the state comes back.
        when(service.setBankAccount(CALLER, "btok_1")).thenReturn(new PayoutAccountResponse("READY", "2874", "PLN"));

        mockMvc.perform(put(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"btok_1\", \"tosAccepted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.last4").value("2874"))
                .andExpect(jsonPath("$.currency").value("PLN"));
    }

    @Test
    void termsNotAcceptedAreA400() throws Exception {
        // Guards opening a connected account for a user who never accepted Stripe's agreement.
        mockMvc.perform(put(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"btok_1\", \"tosAccepted\": false}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void anythingButABankTokenIsRefusedWithoutBeingQuoted() throws Exception {
        // Guards raw bank details reaching Payment Service or Stripe, and a refused value echoed into a detail that
        // GlobalExceptionHandler logs.
        String response = mockMvc.perform(put(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"PL61109010140000071219812874\", \"tosAccepted\": true}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("PL61109010140000071219812874");
        verifyNoInteractions(service);
    }

    @Test
    void aCallerWithoutAnIdentityIsA401BeforeTheBodyIsChecked() throws Exception {
        // Guards the parameter order: with @CurrentUserId after the body, a caller with no identity would be told to
        // fix the body instead.
        mockMvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void theCallersBankAccountIsRead() throws Exception {
        // Guards a GET answered for another user than the caller.
        when(service.bankAccount(anyString())).thenReturn(new PayoutAccountResponse("NOT_SET", null, null));
        when(service.bankAccount(CALLER)).thenReturn(new PayoutAccountResponse("READY", "2874", "PLN"));

        mockMvc.perform(get(PATH).header("X-User-Id", CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"));
    }
}
