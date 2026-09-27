package com.flowwallet.wallet.api;

import com.flowwallet.wallet.dto.HistoryPage;
import com.flowwallet.wallet.dto.WalletResponse;
import com.flowwallet.wallet.support.ControllerMockMvc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";

    private final WalletService wallets = mock(WalletService.class);
    private final MockMvc mockMvc = ControllerMockMvc.of(new WalletController(wallets));

    @Test
    void aLowerCaseCurrencyInTheBodyReachesTheServiceLikeOneInThePath() throws Exception {
        // Guards a case-sensitive constraint on the body: /api/wallets/usd works, so "usd" in the body must reach
        // the service, which normalises both the same way, instead of being refused at the boundary.
        when(wallets.open(CALLER, "usd")).thenReturn(
                new WalletResponse(BigDecimal.ZERO, "USD", null, null)
        );

        mockMvc.perform(post("/api/wallets")
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"usd\"}"))
                .andExpect(status().isCreated());

        verify(wallets).open(CALLER, "usd");
    }

    @Test
    void aBlankCurrencyIsStillRefusedAtTheBoundary() throws Exception {
        // Guards the body constraint: a blank currency never reaches the service.
        mockMvc.perform(post("/api/wallets")
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(wallets);
    }

    @ParameterizedTest(name = "limit={0} is refused")
    @ValueSource(strings = {"0", "101", "-1"})
    void aHistoryLimitOutsideOneToAHundredIsRefused(String limit) throws Exception {
        // Guards the @Min(1)/@Max(100) bounds, which run only through the method-validation proxy: without it
        // they never ran in a test, and a limit of 0 or a million would reach the query unchecked.
        mockMvc.perform(get("/api/wallets/USD/history")
                        .header("X-User-Id", CALLER)
                        .param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());

        verifyNoInteractions(wallets);
    }

    @ParameterizedTest(name = "limit={0} is accepted")
    @ValueSource(strings = {"1", "100"})
    void theHistoryLimitBoundsThemselvesAreAccepted(String limit) throws Exception {
        // Guards an off-by-one in the bounds, which would refuse the smallest or the largest page.
        when(wallets.history(anyString(), anyString(), any(), anyInt())).thenReturn(new HistoryPage(List.of(), null));

        mockMvc.perform(get("/api/wallets/USD/history")
                        .header("X-User-Id", CALLER)
                        .param("limit", limit))
                .andExpect(status().isOk());

        verify(wallets).history(CALLER, "USD", null, Integer.parseInt(limit));
    }

    @Test
    void aNonNumericHistoryCursorIsRefused() throws Exception {
        // Guards the cursor's type: before is an entry number, and anything else is a bad request, not a 500.
        mockMvc.perform(get("/api/wallets/USD/history")
                        .header("X-User-Id", CALLER)
                        .param("before", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(wallets);
    }
}
