package com.flowwallet.wallet.bankaccount;

import com.flowwallet.wallet.support.ControllerMockMvc;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BankAccountControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";
    private static final String PATH = "/api/wallets/bank-account";
    private static final String BODY = "{\"token\": \"btok_1\", \"tosAccepted\": true}";

    private final BankAccountService service = mock(BankAccountService.class);
    private final MockMvc mockMvc = ControllerMockMvc.of(new BankAccountController(service));

    @Test
    void aTokenWithTheTermsAcceptedSetsTheCallersBankAccount() throws Exception {
        // Guards the endpoint's happy path through the real validation and identity resolver.
        when(service.set(eq(CALLER), any())).thenReturn(new BankAccountResponse("READY", "2874", "PLN"));

        mockMvc.perform(put(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.last4").value("2874"));
    }

    @Test
    void anAcceptThatRulesOutJsonIsA406BeforeStripeIsCalled() throws Exception {
        // Guards opening a connected account and spending the token for a client that cannot read the answer.
        mockMvc.perform(put(PATH)
                        .header("X-User-Id", CALLER)
                        .accept(MediaType.TEXT_PLAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotAcceptable());

        verifyNoInteractions(service);
    }

    @Test
    void anythingButABankTokenOrTermsNotAcceptedIsA400WithoutTheValueQuoted() throws Exception {
        // Guards raw bank details leaving the wallet, and a refused value echoed into a logged detail.
        String response = mockMvc.perform(put(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"PL61109010140000071219812874\", \"tosAccepted\": true}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(put(PATH)
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"btok_1\", \"tosAccepted\": false}"))
                .andExpect(status().isBadRequest());

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
        // Guards the read reaching the relay without the caller's identity.
        when(service.get(CALLER)).thenReturn(new BankAccountResponse("NOT_SET", null, null));

        mockMvc.perform(get(PATH).header("X-User-Id", CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_SET"));
    }
}
