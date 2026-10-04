package com.flowwallet.wallet.deposit;

import com.flowwallet.wallet.support.ControllerMockMvc;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The identity, key and content rules are enforced by argument resolution and annotations on the controller, so
 * they only show up when a request actually passes through Spring MVC.
 */
class DepositControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";

    private final DepositService deposits = mock(DepositService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerMockMvc.of(new DepositController(deposits));

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
            "c232ab00-9414-11ec-b3c8-9f6bdeced846, version 1",
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
            "4c9a1b2e-1f3d-4a5b-8c7d-9e0f-a2b3c4d",
            "\uFF14c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d",
    })
    void refusesAnythingThatIsNotAUsableUuid(String key) throws Exception {
        // Guards the key rule drifting from the transfer's: Hibernate's @UUID answered a fifth dash in 36
        // characters with a 500 and accepted a fullwidth digit, which the shared ASCII pattern refuses with 400.
        Assertions.assertThat(deposit(key)).isEqualTo(400);
        verify(deposits, never()).start(anyString(), anyString(), anyString(), any());
    }

    @Test
    void anUnidentifiedCallerIsRefusedWith401BeforeTheKeyAndBodyAreChecked() throws Exception {
        // Guards the parameter order: with @CurrentUserId declared last, a caller with no X-User-Id was told
        // about its key or its body (400) instead of being refused (401).
        int answer = mockMvc.perform(post("/api/wallets/USD/deposits")
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": "))
                .andReturn().getResponse().getStatus();

        Assertions.assertThat(answer).isEqualTo(401);
        verifyNoInteractions(deposits);
    }

    @Test
    void aClientThatCannotReadJsonIsRefusedBeforePaymentServiceIsCalled() throws Exception {
        // Guards a 406 that arrives after the payment intent exists. Negotiated only once the handler has
        // returned, the answer would fail after Payment Service created the intent, and a client that treats a
        // 4xx as final would never finish or retry the payment.
        int answer = mockMvc.perform(post("/api/wallets/USD/deposits")
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 25.00}")
                        .accept(MediaType.APPLICATION_XML))
                .andReturn().getResponse().getStatus();

        Assertions.assertThat(answer).isEqualTo(406);
        verifyNoInteractions(deposits);
    }

    @ParameterizedTest(name = "amount {0} is accepted")
    @CsvSource(delimiter = '|', value = {"\"25.00\"", "\" 25.00 \"", "25.00", "2.5E+1"})
    void anAmountIsAcceptedAsADecimalStringOrAJsonNumber(String amount) throws Exception {
        // Guards the documented string form being refused, or the number form that existing clients send
        // breaking (docs/adr/0030-amounts-in-responses-are-decimal-strings.md).
        int status = mockMvc.perform(post("/api/wallets/USD/deposits")
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": " + amount + "}"))
                .andReturn().getResponse().getStatus();

        Assertions.assertThat(status).isEqualTo(200);
        verify(deposits).start(
                eq(CALLER),
                eq("USD"),
                eq(CALLER),
                argThat(request -> request.amount().compareTo(new BigDecimal("25")) == 0)
        );
    }
}
