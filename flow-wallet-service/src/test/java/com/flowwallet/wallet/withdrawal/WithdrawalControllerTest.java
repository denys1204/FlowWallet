package com.flowwallet.wallet.withdrawal;

import com.flowwallet.wallet.api.InvalidCurrencyException;
import com.flowwallet.wallet.api.WalletNotFoundException;
import com.flowwallet.wallet.deposit.PaymentUnavailableException;
import com.flowwallet.wallet.support.ControllerMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The identity, key and body rules are enforced by argument resolution and annotations on the controller, so they
 * only show up when a request actually passes through Spring MVC.
 */
class WithdrawalControllerTest {
    private static final String CALLER = "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d";
    private static final String KEY = "6F1D4C2A-8B3E-4F5A-9C7D-1E2F3A4B5C6D";
    private static final String REFERENCE = "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d";
    private static final String COLLECTION = "/api/wallets/pln/withdrawals";
    private static final String BODY = "{\"amount\": \"75.00\"}";
    private static final String RECEIPT_JSON = "{\"reference\":\"6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d\","
            + "\"amount\":\"75.00\",\"currency\":\"PLN\",\"balanceAfter\":\"25.00\"}";
    private static final WithdrawalResponse RECEIPT = new WithdrawalResponse(REFERENCE, "75.00", "PLN", "25.00");

    private final ObjectMapper mapper = new ObjectMapper();
    private final WithdrawalService withdrawals = mock(WithdrawalService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerMockMvc.of(new WithdrawalController(withdrawals));

        when(withdrawals.withdraw(anyString(), anyString(), anyString(), any())).thenReturn(RECEIPT);
    }

    private MockHttpServletRequestBuilder withdrawal(String content) {
        return post(COLLECTION).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private MockHttpServletRequestBuilder withdrawal(String content, String key) {
        return withdrawal(content).header("X-User-Id", CALLER).header("Idempotency-Key", key);
    }

    private MockHttpServletRequestBuilder statusOf(String reference) {
        return get(COLLECTION + "/" + reference).header("X-User-Id", CALLER);
    }

    private JsonNode problem(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void aWithdrawalIsAcceptedWith202ALocationThatNamesItAndTheReceipt() throws Exception {
        // Guards a 200 creeping in, which would say the payout is done, a Location that does not address the
        // withdrawal the receipt names, and a receipt that does not reach the caller as it is.
        mockMvc.perform(withdrawal(BODY, KEY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/wallets/PLN/withdrawals/" + REFERENCE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(RECEIPT_JSON));

        verify(withdrawals).withdraw(CALLER, "pln", KEY, new WithdrawalRequest(new BigDecimal("75.00")));
    }

    @Test
    void theLocationIsAPathFromTheNormalisedCurrencyAndTheLowerCasedKeyNeverFromTheRequestHost() throws Exception {
        // Guards a Location built from the request: it would name the service's own address (127.0.0.1:8081) behind
        // the gateway, and echo the caller's casing of the currency and the key, so a replay written differently
        // would get another Location.
        String location = mockMvc.perform(post("http://127.0.0.1:8081/api/wallets/pln/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location).isEqualTo("/api/wallets/PLN/withdrawals/6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d");
        URI uri = URI.create(location);
        assertThat(uri.isAbsolute()).isFalse();
        assertThat(uri.getHost()).isNull();
        assertThat(location).doesNotContain("127.0.0.1").doesNotContain("8081").doesNotContain(KEY);
    }

    @Test
    void aReplayMatchesTheFirstAnswerInStatusLocationAndBody() throws Exception {
        // Guards a replay answered differently from the first request: a status, a Location or a body that follows
        // the request (its host, its path casing, the key's casing) instead of the receipt, so a client that kept
        // the first answer and a client that repeated the call would be told two things about one withdrawal.
        MvcResult first = mockMvc.perform(post("http://127.0.0.1:8081/api/wallets/pln/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", KEY))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("http://localhost:8080/api/wallets/PLN/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 75}")
                        .header("X-User-Id", CALLER)
                        .header("Idempotency-Key", REFERENCE))
                .andReturn();

        verify(withdrawals).withdraw(CALLER, "pln", KEY, new WithdrawalRequest(new BigDecimal("75.00")));
        verify(withdrawals).withdraw(CALLER, "PLN", REFERENCE, new WithdrawalRequest(new BigDecimal("75")));
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        assertThat(replay.getResponse().getStatus()).isEqualTo(202);
        assertThat(replay.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(replay.getResponse().getContentAsByteArray()).isEqualTo(first.getResponse().getContentAsByteArray());
    }

    @ParameterizedTest(name = "amount {0} is accepted")
    @CsvSource(delimiter = '|', value = {"\"25.00\"", "\" 25.00 \"", "25.00", "2.5E+1"})
    void theAmountIsTakenAsAStringOrAJsonNumber(String amount) throws Exception {
        // Guards the documented string form being refused, or the number form that existing clients send breaking.
        // Responses print money as strings (docs/adr/0030-amounts-in-responses-are-decimal-strings.md), so a client
        // that sends an amount back as it read it sends a string.
        mockMvc.perform(withdrawal("{\"amount\": " + amount + "}", KEY))
                .andExpect(status().isAccepted());

        verify(withdrawals).withdraw(
                eq(CALLER),
                eq("pln"),
                eq(KEY),
                argThat(request -> request.amount().compareTo(new BigDecimal("25")) == 0)
        );
    }

    @Test
    void anAcceptHeaderThatRulesOutJsonIs406BeforeTheServiceRuns() throws Exception {
        // Guards a 406 that arrives after the wallet was debited. Negotiated only once the handler has returned, a
        // receipt no converter can write would fail after the debit committed, and a client that treats a 4xx as
        // final would believe nothing happened.
        mockMvc.perform(withdrawal(BODY, KEY).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());

        verifyNoInteractions(withdrawals);
    }

    @Test
    void aCallerWithoutAnIdentityGets401BeforeTheKeyAndTheBodyAreLookedAt() throws Exception {
        // Guards the parameter order regressing. With @CurrentUserId anywhere but first, a caller with no
        // X-User-Id would be told about its key or its body (400) instead of being refused (401).
        mockMvc.perform(post(COLLECTION)
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": "))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(withdrawals);
    }

    @Test
    void aCallerWithoutAnIdentityGets401OnTheStatusReadBeforeTheReferenceIsLookedAt() throws Exception {
        // Guards the same order on the read: a reference that is not a UUID must not be reported to a caller that is
        // not identified.
        mockMvc.perform(get(COLLECTION + "/not-a-uuid"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(withdrawals);
    }

    @ParameterizedTest(name = "{1} answers 400")
    @CsvSource(nullValues = "missing", value = {
            "missing,                              no key",
            "00000000-0000-0000-0000-000000000000, the nil UUID",
            "1-1-1-1-1,                            a string UUID.fromString accepts",
            "not-a-uuid,                           not a UUID",
            "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b-c6d, a fifth dash in 36 characters",
            "６f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d, a fullwidth digit",
            "6f1d4c2a-8b3e-9f5a-9c7d-1e2f3a4b5c6d, version 9",
            "6f1d4c2a-8b3e-4f5a-ec7d-1e2f3a4b5c6d, variant nibble e"
    })
    void aMissingOrMalformedKeyIs400WithoutQuotingIt(String key, String description) throws Exception {
        // Guards a withdrawal made without idempotency, where a lost response and a retry pay out twice; Hibernate's
        // @UUID coming back, which throws on a fifth dash in 36 characters (a 500) and accepts non-ASCII digits; the
        // header constraint not running through the proxy at all; and a refused key echoed into a detail that is
        // logged (docs/adr/0026-problem-details-never-quote-rejected-input.md).
        MockHttpServletRequestBuilder request = withdrawal(BODY).header("X-User-Id", CALLER);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }

        MvcResult result = mockMvc.perform(request).andExpect(status().isBadRequest()).andReturn();

        if (key != null) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(key);
            assertThat(problem(result).get("errors").get(0).stringValue()).endsWith("Idempotency-Key must be a UUID");
        }
        verifyNoInteractions(withdrawals);
    }

    @ParameterizedTest(name = "{1} is accepted")
    @CsvSource({
            "c232ab00-9414-11ec-b3c8-9f6bdeced846, a version 1 key",
            "4c9a1b2e-1f3d-4a5b-8c7d-9e0f1a2b3c4d, a version 4 key",
            "886313e1-3b8a-5372-9b90-0c9aee199e5d, a version 5 key",
            "018f3a2b-7c4d-7e5f-8a9b-0c1d2e3f4a5b, a version 7 key",
            "6F1D4C2A-8B3E-4F5A-9C7D-1E2F3A4B5C6D, an upper-case key"
    })
    void anyVersionOfUuidInEitherCaseIsAcceptedAsAKey(String key, String description) throws Exception {
        // Guards a copied version list that refuses the version-7 keys client libraries generate by default.
        mockMvc.perform(withdrawal(BODY, key)).andExpect(status().isAccepted());

        verify(withdrawals).withdraw(eq(CALLER), eq("pln"), eq(key), any());
    }

    static Stream<Arguments> unusableAmounts() {
        return Stream.of(
                arguments("{}", "amount Amount is required"),
                arguments("{\"amount\": null}", "amount Amount is required"),
                arguments("{\"amount\": \"\"}", "amount Amount is required"),
                arguments("{\"amount\": 0}", "amount Amount must be greater than zero"),
                arguments("{\"amount\": -5.00}", "amount Amount must be greater than zero"),
                arguments("{\"amount\": \"-0.01\"}", "amount Amount must be greater than zero")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableAmounts")
    void aMissingZeroOrNegativeAmountIs400InErrors(String body, String error) throws Exception {
        // Guards the body rules: a withdrawal of nothing, and a negative one that would credit the wallet through a
        // debit. The failing field is named in errors, so a client can show it.
        mockMvc.perform(withdrawal(body, KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(error))
                .andExpect(jsonPath("$.errors.length()").value(1));

        verifyNoInteractions(withdrawals);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{\"amount\": \"abc\"}",
            "{\"amount\": \"abc",
            "abc"
    })
    void anAmountThatIsNotANumberIsRefusedWithoutQuotingIt(String body) throws Exception {
        // Guards a string amount reaching the service, and the refusal echoing the caller's input into a detail
        // that is logged (docs/adr/0026-problem-details-never-quote-rejected-input.md).
        String answer = mockMvc.perform(withdrawal(body, KEY))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(answer).doesNotContain("abc");
        verifyNoInteractions(withdrawals);
    }

    @Test
    void aRecordedRefusalIs422AndItsDetailSaysTheKeyIsSpent() throws Exception {
        // Guards a refusal that does not tell the client its key is spent: the same key keeps getting this answer,
        // and a client that retried it after a top-up would wait for a payout that never comes.
        when(withdrawals.withdraw(anyString(), anyString(), anyString(), any()))
                .thenThrow(new WithdrawalRefusedException(WithdrawalRefusalReason.INSUFFICIENT_FUNDS, "PLN"))
                .thenThrow(new WithdrawalRefusedException(WithdrawalRefusalReason.NO_BANK_ACCOUNT, "PLN"));

        mockMvc.perform(withdrawal(BODY, KEY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient funds in the PLN wallet. This Idempotency-Key is "
                        + "spent; withdraw a smaller amount or top up, then use a new key."));
        mockMvc.perform(withdrawal(BODY, KEY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("No bank account is ready for payouts in PLN. This "
                        + "Idempotency-Key is spent; set a bank account, then use a new key."));
    }

    @Test
    void aShortReserveIs503AndSaysNothingWasDebited() throws Exception {
        // Guards a short platform reserve answered as a 500 or a 502, and a detail that does not promise that
        // nothing was debited, which a client must know before it repeats the call with the same key.
        when(withdrawals.withdraw(anyString(), anyString(), anyString(), any()))
                .thenThrow(new WithdrawalsUnavailableException());

        mockMvc.perform(withdrawal(BODY, KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Withdrawals are temporarily unavailable. Nothing was debited; "
                        + "retry later with the same Idempotency-Key."));
    }

    static Stream<Arguments> problemsOfTheService() {
        return Stream.of(
                arguments(
                        new ConflictingWithdrawalException(),
                        409,
                        "This Idempotency-Key was already used for a different withdrawal. Retry with a new key."
                ),
                arguments(
                        new WithdrawalRejectedException("The amount is below the minimum for PLN."),
                        400,
                        "The amount is below the minimum for PLN."
                ),
                arguments(
                        new PaymentUnavailableException("Payment Service is unavailable. Nothing was debited. "
                                + "Retry with the same Idempotency-Key."),
                        502,
                        "Payment Service is unavailable. Nothing was debited. Retry with the same Idempotency-Key."
                ),
                arguments(
                        new WithdrawalBusyException(new CannotAcquireLockException("deadlock detected")),
                        503,
                        "The wallet is busy and nothing was debited. Retry with the same Idempotency-Key."
                ),
                arguments(new WalletNotFoundException("PLN"), 404, "No PLN wallet"),
                arguments(new InvalidCurrencyException(), 400, "Not an ISO 4217 currency code"),
                arguments(
                        new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
                        503,
                        "Service temporarily unavailable; retry the request, with the same Idempotency-Key if it has "
                                + "one"
                )
        );
    }

    @ParameterizedTest(name = "answers {1}")
    @MethodSource("problemsOfTheService")
    void everyOtherRefusalOfTheServiceKeepsItsStatusAndItsDetail(RuntimeException refusal, int code, String detail)
            throws Exception {
        // Guards a refusal reaching the caller as a bare 500, or with another detail than the one that tells the
        // client what to do next: a new key after a conflict, the same key after a 502 or a busy wallet.
        when(withdrawals.withdraw(anyString(), anyString(), anyString(), any())).thenThrow(refusal);

        mockMvc.perform(withdrawal(BODY, KEY))
                .andExpect(status().is(code))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    static Stream<Arguments> statusReads() {
        return Stream.of(
                arguments("REFUSED", "INSUFFICIENT_FUNDS", "\"INSUFFICIENT_FUNDS\""),
                arguments("REFUSED", "NO_BANK_ACCOUNT", "\"NO_BANK_ACCOUNT\""),
                arguments("RETURN_PENDING", "PAYOUT_FAILED", "\"PAYOUT_FAILED\""),
                arguments("RETURNED", "SOMETHING_NEW", "\"SOMETHING_NEW\""),
                arguments("PENDING", null, "null"),
                arguments("PAID", null, "null")
        );
    }

    @ParameterizedTest(name = "{0} with reason {1}")
    @MethodSource("statusReads")
    void theStatusReadShowsTheReasonThatAppliesAndNullOtherwise(String state, String reason, String reasonJson)
            throws Exception {
        // Guards a reason dropped from the body when it is null (a client reading the field would see it missing
        // and not null), a reason that does not reach the caller, and an unknown return reason failing the read:
        // the set is open and a client shows it as it is.
        when(withdrawals.read(CALLER, "pln", REFERENCE))
                .thenReturn(new WithdrawalStatusResponse(REFERENCE, "75.00", "PLN", state, reason));

        mockMvc.perform(statusOf(REFERENCE))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"reference\":\"6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d\","
                        + "\"amount\":\"75.00\",\"currency\":\"PLN\",\"status\":\"" + state + "\","
                        + "\"reason\":" + reasonJson + "}"));
    }

    @Test
    void theStatusReadPassesTheReferenceAndTheCurrencyAsTheCallerWroteThem() throws Exception {
        // Guards the read being answered for another caller or another casing than the one the service normalises:
        // the controller hands the path over untouched and the service answers for the caller's wallet.
        when(withdrawals.read(anyString(), anyString(), anyString())).thenReturn(
                new WithdrawalStatusResponse(REFERENCE, "75.00", "PLN", "PAID", null)
        );

        mockMvc.perform(statusOf(KEY)).andExpect(status().isOk());

        verify(withdrawals).read(CALLER, "pln", KEY);
    }

    @ParameterizedTest(name = "{1} answers 400")
    @CsvSource({
            "00000000-0000-0000-0000-000000000000, the nil UUID",
            "1-1-1-1-1,                            a string UUID.fromString accepts",
            "not-a-uuid,                           not a UUID",
            "6f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b-c6d, a fifth dash in 36 characters",
            "６f1d4c2a-8b3e-4f5a-9c7d-1e2f3a4b5c6d, a fullwidth digit"
    })
    void aReferenceThatIsNotAUuidIs400InErrorsWithoutQuotingIt(String reference, String description) throws Exception {
        // Guards a reference that is looked up as it comes, and the refusal echoing it into a detail that is logged
        // (docs/adr/0026-problem-details-never-quote-rejected-input.md). The request path is the problem's
        // instance, so the value is checked in the detail and the errors, where this rule applies.
        MvcResult result = mockMvc.perform(statusOf(reference)).andExpect(status().isBadRequest()).andReturn();

        JsonNode problem = problem(result);
        assertThat(problem.get("errors")).hasSize(1);
        assertThat(problem.get("errors").get(0).stringValue())
                .endsWith("reference must be a UUID")
                .doesNotContain(reference);
        assertThat(problem.get("detail").stringValue()).doesNotContain(reference);
        verifyNoInteractions(withdrawals);
    }

    @Test
    void aMissingWithdrawalAndAMissingWalletAre404sWithDifferentDetails() throws Exception {
        // Guards the two 404s sounding alike: a client that opens the wallet when the withdrawal is unknown would
        // open one it has, and one that stops when the wallet is missing would give up on a withdrawal it can still
        // read once the wallet exists.
        when(withdrawals.read(anyString(), anyString(), anyString()))
                .thenThrow(new WalletNotFoundException("PLN"))
                .thenThrow(new WithdrawalNotFoundException("PLN"));

        MvcResult noWallet = mockMvc.perform(statusOf(REFERENCE)).andExpect(status().isNotFound()).andReturn();
        MvcResult noWithdrawal = mockMvc.perform(statusOf(REFERENCE)).andExpect(status().isNotFound()).andReturn();

        assertThat(problem(noWallet).get("detail").stringValue()).isEqualTo("No PLN wallet");
        assertThat(problem(noWithdrawal).get("detail").stringValue()).isEqualTo("No such withdrawal in the PLN wallet");
    }

    @Test
    void aWalletThatIsMissingIsA404ForAWithdrawalToo() throws Exception {
        // Guards the POST answering a missing wallet as anything but the 404 the read gives, which tells the client
        // to open and fund the wallet before it uses a new key.
        when(withdrawals.withdraw(anyString(), anyString(), anyString(), any()))
                .thenThrow(new WalletNotFoundException("PLN"));

        mockMvc.perform(withdrawal(BODY, KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No PLN wallet"));
    }
}
