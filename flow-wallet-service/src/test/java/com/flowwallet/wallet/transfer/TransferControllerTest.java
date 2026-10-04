package com.flowwallet.wallet.transfer;

import com.flowwallet.wallet.support.ControllerMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The identity, key and body rules are enforced by argument resolution and annotations on the controller, so
 * they only show up when a request actually passes through Spring MVC.
 */
class TransferControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";
    private static final String RECIPIENT = "018f3a2b-7c4d-7e5f-8a9b-0c1d2e3f4a5b";
    private static final String KEY = "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44";

    private final TransferService transfers = mock(TransferService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerMockMvc.of(new TransferController(transfers));

        when(transfers.transfer(anyString(), anyString(), anyString(), any())).thenReturn(new TransferResponse(
                KEY, RECIPIENT, "25.00", "USD", "75.00"
        ));
    }

    private MockHttpServletRequestBuilder transfer(String body) {
        return post("/api/wallets/usd/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private int answerTo(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String body(String to, String amount) {
        return to == null
                ? "{\"amount\": " + amount + "}"
                : "{\"to\": \"" + to + "\", \"amount\": " + amount + "}";
    }

    @Test
    void anUnidentifiedCallerIsRefusedWith401BeforeTheBodyIsValidated() throws Exception {
        // Guards the parameter order regressing. With @CurrentUserId anywhere but first, a caller with no
        // X-User-Id would be told about its missing key or its body (400) instead of being refused (401).
        int answer = answerTo(transfer("{\"to\": \"alice\", \"amount\": -1}"));

        assertThat(answer).isEqualTo(401);
        verifyNoInteractions(transfers);
    }

    @ParameterizedTest(name = "{1} answers {2}")
    @CsvSource(nullValues = "missing", value = {
            "c232ab00-9414-11ec-b3c8-9f6bdeced846, version 1,                         400",
            "a3bb189e-8bf9-3888-9912-ace4e6543002, version 3,                         400",
            "886313e1-3b8a-5372-9b90-0c9aee199e5d, version 5,                         400",
            "00000000-0000-0000-0000-000000000000, the nil UUID,                      400",
            "4c9a1b2e-1f3d-4a5b-0c7d-9e0f1a2b3c4d, variant nibble 0,                  400",
            "4c9a1b2e-1f3d-4a5b-cc7d-9e0f1a2b3c4d, variant nibble c,                  400",
            "alice,                                a name,                            400",
            "missing,                              a missing recipient,               400",
            "4c9a1b2e-1f3d-4a5b-8c7d-9e0f-a2b3c4d, a fifth dash in 36 characters,     400",
            "\uFF14c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d, a fullwidth digit,           400",
            "4C9A1B2E-1F3D-4A5B-8C7D-9E0F1A2B3C4D, an upper-case version 4,           200",
            "018F3A2B-7C4D-7E5F-8A9B-0C1D2E3F4A5B, an upper-case version 7,           200"
    })
    void theRecipientMustBeARandomBasedUserId(String to, String description, int expected) throws Exception {
        // Guards the recipient rule drifting from the one CurrentUserIdResolver applies to callers, and
        // Hibernate's @UUID coming back: it throws on a fifth dash in 36 characters, which the platform handler
        // answers with a 500, and it accepts non-ASCII digits the resolver refuses. An id no caller can have
        // can own no wallet, so it is refused before any query; an id a caller can have must never be refused
        // over its case.
        int answer = answerTo(transfer(body(to, "25.00"))
                .header("X-User-Id", CALLER)
                .header("Idempotency-Key", KEY));

        assertThat(answer).isEqualTo(expected);
        verify(transfers, times(expected == 200 ? 1 : 0)).transfer(anyString(), anyString(), anyString(), any());
    }

    @ParameterizedTest(name = "{1} answers {2}")
    @CsvSource(nullValues = "missing", value = {
            "missing,                              no key,                            400",
            "00000000-0000-0000-0000-000000000000, the nil UUID,                      400",
            "1-1-1-1-1,                            a string UUID.fromString accepts,  400",
            "not-a-uuid,                           not a UUID,                        400",
            "7e1855b3-4d95-4a72-a0c9-ef0d78be-e44, a fifth dash in 36 characters,     400",
            "c232ab00-9414-11ec-b3c8-9f6bdeced846, a version 1 key,                   200",
            "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d, a version 4 key,                   200",
            "886313e1-3b8a-5372-9b90-0c9aee199e5d, a version 5 key,                   200",
            "018f3a2b-7c4d-7e5f-8a9b-0c1d2e3f4a5b, a version 7 key,                   200",
            "7E1855B3-4D95-4A72-A0C9-EF0D78BE2E44, an upper-case key,                 200"
    })
    void theIdempotencyKeyIsRequiredAndAnyUuidVersionIsAccepted(String key, String description, int expected)
            throws Exception {
        // Guards a transfer made without idempotency, where a lost response and a retry move the money twice;
        // a copied version list that refuses the version-7 keys client libraries generate by default; a key
        // with a fifth dash crashing Hibernate's @UUID into a 500; and the header constraint not running
        // through the proxy at all.
        MockHttpServletRequestBuilder request = transfer(body(RECIPIENT, "25.00")).header("X-User-Id", CALLER);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }

        assertThat(answerTo(request)).isEqualTo(expected);
        verify(transfers, times(expected == 200 ? 1 : 0)).transfer(anyString(), anyString(), anyString(), any());
    }

    @ParameterizedTest(name = "an amount of {0} is refused")
    @CsvSource(nullValues = "missing", value = {"missing", "0", "-5.00", "\"twenty\""})
    void aMissingZeroOrNegativeAmountIsRefused(String amount) throws Exception {
        // Guards the body rules: a movement of nothing, a negative transfer that would pull money from the
        // recipient, and an amount that is not a number at all.
        String body = amount == null ? "{\"to\": \"" + RECIPIENT + "\"}" : body(RECIPIENT, amount);

        int answer = answerTo(transfer(body)
                .header("X-User-Id", CALLER)
                .header("Idempotency-Key", KEY));

        assertThat(answer).isEqualTo(400);
        verifyNoInteractions(transfers);
    }

    @Test
    void aClientThatCannotReadJsonIsRefusedBeforeTheServiceRuns() throws Exception {
        // Guards a 406 that arrives after the money moved. Negotiated only once the handler has returned, a
        // receipt no converter can write would fail after the transfer committed, and a client that treats a
        // 4xx as final would believe it never happened.
        int answer = answerTo(transfer(body(RECIPIENT, "25.00"))
                .header("X-User-Id", CALLER)
                .header("Idempotency-Key", KEY)
                .accept(MediaType.APPLICATION_XML));

        assertThat(answer).isEqualTo(406);
        verifyNoInteractions(transfers);
    }

    @Test
    void aDatabaseThatCannotBeReachedAnswers503() throws Exception {
        // Guards the platform mapping losing to the last-resort handler: a transaction that cannot begin because
        // Postgres is down or the pool timed out must answer 503, whose remedy is a retry with the same key, and
        // not a 500 that tells the client the fault is permanent.
        when(transfers.transfer(anyString(), anyString(), anyString(), any())).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction")
        );

        mockMvc.perform(transfer(body(RECIPIENT, "25.00"))
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Service temporarily unavailable; retry the request, "
                        + "with the same Idempotency-Key if it has one"));
    }

    @Test
    void aTransferAnswers200() throws Exception {
        // Guards a 201 creeping in. A replay answers 200, and the first answer must match it in status as well
        // as in body, or a client would be told two different things about one transfer.
        mockMvc.perform(transfer(body(RECIPIENT, "25.00"))
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(KEY))
                .andExpect(jsonPath("$.to").value(RECIPIENT));

        verify(transfers).transfer(CALLER, "usd", KEY, new TransferRequest(RECIPIENT, new BigDecimal("25.00")));
    }

    @ParameterizedTest(name = "amount {0} is accepted")
    @CsvSource(delimiter = '|', value = {"\"25.00\"", "\" 25.00 \"", "25.00", "2.5E+1"})
    void anAmountIsAcceptedAsADecimalStringOrAJsonNumber(String amount) throws Exception {
        // Guards the documented string form being refused, or the number form that existing clients send
        // breaking. Responses print money as strings (docs/adr/0030-amounts-in-responses-are-decimal-strings.md),
        // so a client that sends an amount back as it read it sends a string.
        mockMvc.perform(transfer(body(RECIPIENT, amount))
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk());

        verify(transfers).transfer(
                eq(CALLER),
                eq("usd"),
                eq(KEY),
                argThat(request -> request.amount().compareTo(new BigDecimal("25")) == 0)
        );
    }

    @Test
    void aBlankStringAmountIsRefusedAsMissing() throws Exception {
        // Guards an empty string slipping through as some amount: Jackson reads "" as no value, so the request
        // gets the same 400 as one without an amount.
        mockMvc.perform(transfer(body(RECIPIENT, "\"\""))
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("amount Amount is required"));

        verifyNoInteractions(transfers);
    }

    @Test
    void anAmountThatIsNotANumberIsRefusedWithoutQuotingIt() throws Exception {
        // Guards a string amount reaching the transfer, and the refusal echoing the caller's input into a detail
        // that is logged (docs/adr/0026-problem-details-never-quote-rejected-input.md).
        String answer = mockMvc.perform(transfer(body(RECIPIENT, "\"abc\""))
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(answer).doesNotContain("abc");
        verifyNoInteractions(transfers);
    }
}
