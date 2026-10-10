package com.flowwallet.wallet.withdrawal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.flowwallet.platform.exception.ApiException;
import com.flowwallet.wallet.deposit.PaymentUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WithdrawalPreflightTest {
    private static final String USER = "0b9e5c1a-7d2f-4a3b-8c4d-5e6f7a8b9c0d";
    private static final String REFERENCE = "7e1855b3-4d95-4a72-a0c9-ef0d78be2e44";
    private static final String BODY_TEXT = "SECRET-BODY-TEXT";
    private static final WithdrawalTerms TERMS =
            new WithdrawalTerms(USER, "PLN", REFERENCE, new BigDecimal("75.0000"));

    private final Logger logger = (Logger) LoggerFactory.getLogger(WithdrawalPreflight.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private final PayoutClient payouts = mock(PayoutClient.class);
    private final WithdrawalPreflight preflight = new WithdrawalPreflight(payouts, new ObjectMapper());

    private static HttpClientErrorException clientError(int status, String body) {
        return HttpClientErrorException.create(
                HttpStatusCode.valueOf(status),
                "refused",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }

    private static HttpServerErrorException serverError(int status, String body) {
        return HttpServerErrorException.create(
                HttpStatusCode.valueOf(status),
                "failed",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }

    @BeforeEach
    void attachLogAppender() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logs);
    }

    /**
     * What the preflight logged, each record as its level, a space and its formatted message.
     */
    private List<String> logged() {
        return logs.list.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
    }

    /**
     * Every character the preflight logged, the stack traces of attached exceptions included, for the checks that
     * something must never appear anywhere in the log.
     */
    private String everythingLogged() {
        return logs.list.stream()
                .map(event -> event.getFormattedMessage() + ThrowableProxyUtil.asString(event.getThrowableProxy()))
                .collect(Collectors.joining("\n"));
    }

    private void paymentServiceAnswers(PayoutPreflightResult result) {
        when(payouts.preflight(anyString(), any())).thenReturn(result);
    }

    @Test
    void aReadyBankAccountWithAReserveIsReady() {
        // Guards a usable answer being read as a refusal, which would hold every withdrawal, and a routine
        // success writing a log line per request.
        paymentServiceAnswers(new PayoutPreflightResult("READY", true));

        assertThat(preflight.check(TERMS)).isEqualTo(BankAccountState.READY);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void aShortReserveWithAReadyBankAccountIsA503() {
        // Guards a short reserve reaching the handler, which would debit a payout the platform cannot make, and a
        // detail that tells the caller about the platform's balance.
        paymentServiceAnswers(new PayoutPreflightResult("READY", false));

        assertThatThrownBy(() -> preflight.check(TERMS))
                .isExactlyInstanceOf(WithdrawalsUnavailableException.class)
                .hasMessage("Withdrawals are temporarily unavailable. Nothing was debited; retry later with the "
                        + "same Idempotency-Key.")
                .extracting("status")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest(name = "a reserve of {0}")
    @CsvSource(nullValues = "missing", value = {"missing", "true", "false"})
    void aBankAccountThatIsNotSetIsNotSetWhateverTheReserveSays(Boolean reserve) {
        // Guards the reserve deciding a withdrawal that has no bank account: it must reach the handler as NOT_SET so
        // the refusal is recorded and the key spent, and not be held with a 503 that invites a retry.
        paymentServiceAnswers(new PayoutPreflightResult("NOT_SET", reserve));

        assertThat(preflight.check(TERMS)).isEqualTo(BankAccountState.NOT_SET);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void thePreflightCarriesTheCallersIdTheUpperCaseCurrencyAndTheCanonicalAmount() {
        // Guards a preflight made for another user than the caller, or with an amount at another scale than the one
        // that is debited: the record compares the amount's scale, so 75.00 would not match 75.0000.
        paymentServiceAnswers(new PayoutPreflightResult("READY", true));

        preflight.check(TERMS);

        verify(payouts).preflight(USER, new PayoutPreflightCommand("PLN", new BigDecimal("75.0000")));
    }

    static Stream<Arguments> termsRefusals() {
        return Stream.of(
                arguments(
                        named("a detail", "{\"detail\": \"The amount is below the minimum for PLN.\"}"),
                        "The amount is below the minimum for PLN."
                ),
                arguments(
                        named("field errors, which win over the generic detail", """
                                {"detail": "Invalid request content.", "errors": ["currency currency must be three \
                                capital letters", "amount amount is required"]}
                                """),
                        "currency currency must be three capital letters; amount amount is required"
                ),
                arguments(
                        named("a body that is not JSON", "<html>Bad Request</html>"),
                        "Payment Service refused the withdrawal."
                ),
                arguments(named("a body with neither", "{}"), "Payment Service refused the withdrawal.")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("termsRefusals")
    void aTermsRefusalIsRelayedAsA400InPaymentServicesWords(String body, String expected) {
        // Guards a refusal of the terms (a currency not paid out, a minimum, a grid) turning into a 502 the caller
        // cannot fix, or Payment Service's reason being lost; and a body that cannot be read throwing instead of
        // falling back to a plain sentence.
        when(payouts.preflight(anyString(), any())).thenThrow(clientError(400, body));

        assertThatThrownBy(() -> preflight.check(TERMS))
                .isExactlyInstanceOf(WithdrawalRejectedException.class)
                .hasMessage(expected)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "401, UNAUTHORIZED",
            "404, NOT_FOUND",
            "405, METHOD_NOT_ALLOWED",
            "409, CONFLICT",
            "415, UNSUPPORTED_MEDIA_TYPE",
            "422, UNPROCESSABLE_CONTENT"
    })
    void anyOtherRefusalIsTheWalletsOwnFaultAndA502ThatLogsNoBody(int status, String name) {
        // Guards a 4xx about the wallet's own request (a missing header, a route an older Payment Service lacks)
        // being passed to the caller as their mistake, and the body that came with it reaching a log line.
        when(payouts.preflight(anyString(), any()))
                .thenThrow(clientError(status, "{\"detail\": \"" + BODY_TEXT + "\"}"));

        assertThatThrownBy(() -> preflight.check(TERMS))
                .isExactlyInstanceOf(PaymentUnavailableException.class)
                .hasMessage("Payment Service refused the request. Nothing was debited.")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(logged()).containsExactly(
                "ERROR Payment Service refused the wallet's own preflight of withdrawal " + REFERENCE
                        + " with " + status + " " + name
        );
        assertThat(everythingLogged()).doesNotContain(BODY_TEXT);
    }

    static Stream<Arguments> failingOrSilentPaymentServices() {
        String unavailable = "Payment Service could not check the withdrawal. Nothing was debited. "
                + "Retry with the same Idempotency-Key.";
        String silent = "Payment Service is unavailable. Nothing was debited. Retry with the same Idempotency-Key.";
        return Stream.of(
                arguments(
                        named("a 500", serverError(500, BODY_TEXT)),
                        unavailable,
                        "WARN Payment Service answered 500 INTERNAL_SERVER_ERROR to the preflight of withdrawal "
                                + REFERENCE
                ),
                arguments(
                        named("a 502 for an unreadable Stripe balance", serverError(502, BODY_TEXT)),
                        unavailable,
                        "WARN Payment Service answered 502 BAD_GATEWAY to the preflight of withdrawal " + REFERENCE
                ),
                arguments(
                        named("a 503 for its database", serverError(503, BODY_TEXT)),
                        unavailable,
                        "WARN Payment Service answered 503 SERVICE_UNAVAILABLE to the preflight of withdrawal "
                                + REFERENCE
                ),
                arguments(
                        named("a read timeout", new ResourceAccessException("I/O error on POST request: timed out")),
                        silent,
                        "WARN Payment Service did not answer the preflight of withdrawal " + REFERENCE
                                + ": I/O error on POST request: timed out"
                ),
                arguments(
                        named("a refused connection", new ResourceAccessException("I/O error: Connection refused")),
                        silent,
                        "WARN Payment Service did not answer the preflight of withdrawal " + REFERENCE
                                + ": I/O error: Connection refused"
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingOrSilentPaymentServices")
    void aFailingOrSilentPaymentServiceIsA502ThatSaysNothingWasDebited(
            RuntimeException failure,
            String detail,
            String logLine
    ) {
        // Guards an outage reported as a 500, a detail that does not promise that nothing was debited and that the
        // same key is safe, and a log line that carries the 5xx body or the user instead of the status and the
        // reference.
        when(payouts.preflight(anyString(), any())).thenThrow(failure);

        assertThatThrownBy(() -> preflight.check(TERMS))
                .isExactlyInstanceOf(PaymentUnavailableException.class)
                .hasMessage(detail)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(logged()).containsExactly(logLine);
        assertThat(everythingLogged()).doesNotContain(BODY_TEXT);
    }

    static Stream<Arguments> unreadableAnswers() {
        String unreadable = "unreadable answer";
        String unknown = "answer the wallet does not know";
        return Stream.of(
                arguments(named("no body", null), unreadable),
                arguments(named("no bank account", new PayoutPreflightResult(null, true)), unknown),
                arguments(named("an empty bank account", new PayoutPreflightResult("", true)), unknown),
                arguments(named("a lower-case READY", new PayoutPreflightResult("ready", true)), unknown),
                arguments(named("a lower-case NOT_SET", new PayoutPreflightResult("not_set", null)), unknown),
                arguments(named("an unknown state", new PayoutPreflightResult("SUSPENDED", true)), unknown),
                arguments(named("READY without a reserve", new PayoutPreflightResult("READY", null)), unknown)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableAnswers")
    void anUnreadableAnswerIsA502AndNeverCountsAsReadyOrNotSet(PayoutPreflightResult result, String logFragment) {
        // Guards an answer the wallet cannot place being read as NOT_SET, which would record a refusal and spend
        // the key for a user whose bank account is fine, or as READY, which would debit on a guess.
        paymentServiceAnswers(result);

        assertThatThrownBy(() -> preflight.check(TERMS))
                .isExactlyInstanceOf(PaymentUnavailableException.class)
                .hasMessage("Payment Service gave an answer the wallet could not read. Nothing was debited. "
                        + "Retry with the same Idempotency-Key.")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(logged())
                .singleElement()
                .asString()
                .startsWith("ERROR ")
                .contains(logFragment)
                .endsWith(" withdrawal " + REFERENCE);
    }

    @Test
    void anyOtherFailureOfTheClientIsAnUnreadableAnswer() {
        // Guards a failure to read the answer (a body that is not JSON, an unknown status line) escaping as a 500
        // for a call whose outcome is known: nothing was debited, and the same key is safe.
        when(payouts.preflight(anyString(), any())).thenThrow(new RestClientException("unknown status"));

        assertThatThrownBy(() -> preflight.check(TERMS))
                .isExactlyInstanceOf(PaymentUnavailableException.class)
                .hasMessage("Payment Service gave an answer the wallet could not read. Nothing was debited. "
                        + "Retry with the same Idempotency-Key.");
        assertThat(logged())
                .singleElement()
                .asString()
                .isEqualTo("ERROR Payment Service gave an unreadable answer to the preflight of withdrawal "
                        + REFERENCE);
    }

    @Test
    void noLogLineOrDetailNamesTheUserOrQuotesABody() {
        // Guards the user id, which spends the user's balance, and a Payment Service body reaching a log line or a
        // detail on any failure path of the preflight. Only the 400's own wording is passed on.
        when(payouts.preflight(anyString(), any()))
                .thenThrow(clientError(400, "{\"detail\": \"Below the minimum.\"}"))
                .thenThrow(clientError(401, BODY_TEXT))
                .thenThrow(clientError(404, BODY_TEXT))
                .thenThrow(serverError(502, BODY_TEXT))
                .thenThrow(serverError(503, BODY_TEXT))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenThrow(new RestClientException("unknown status"))
                .thenReturn(null)
                .thenReturn(new PayoutPreflightResult("SUSPENDED", true))
                .thenReturn(new PayoutPreflightResult("READY", null))
                .thenReturn(new PayoutPreflightResult("READY", false));

        for (int attempt = 0; attempt < 11; attempt++) {
            assertThatThrownBy(() -> preflight.check(TERMS))
                    .isInstanceOf(ApiException.class)
                    .message()
                    .doesNotContain(USER)
                    .doesNotContain(BODY_TEXT);
        }

        assertThat(logs.list)
                .hasSize(9)
                .allSatisfy(line -> assertThat(line.getFormattedMessage()).contains(" withdrawal " + REFERENCE));
        assertThat(everythingLogged()).doesNotContain(USER).doesNotContain(BODY_TEXT);
    }
}
