package com.flowwallet.wallet.api;

import com.flowwallet.platform.security.CurrentUserIdResolver;
import com.flowwallet.platform.web.GlobalExceptionHandler;
import com.flowwallet.wallet.dto.WalletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";

    private final WalletService wallets = mock(WalletService.class);

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new WalletController(wallets))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdResolver())
                .setValidator(validator)
                .build();
    }

    @Test
    void aLowerCaseCurrencyInTheBodyReachesTheServiceLikeOneInThePath() throws Exception {
        // /api/wallets/usd already worked; "usd" in the body was refused by a case-sensitive constraint
        // before the service could normalise it. Both now go through the same normalisation.
        when(wallets.open(CALLER, "usd")).thenReturn(
                new WalletResponse(BigDecimal.ZERO, "USD", null, null)
        );

        mockMvc().perform(post("/api/wallets")
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"usd\"}"))
                .andExpect(status().isCreated());

        verify(wallets).open(CALLER, "usd");
    }

    @Test
    void aBlankCurrencyIsStillRefusedAtTheBoundary() throws Exception {
        mockMvc().perform(post("/api/wallets")
                        .header("X-User-Id", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(wallets);
    }
}
