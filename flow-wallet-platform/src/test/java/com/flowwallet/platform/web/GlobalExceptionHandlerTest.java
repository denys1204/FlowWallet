package com.flowwallet.platform.web;

import com.flowwallet.platform.exception.ApiException;
import com.flowwallet.platform.security.MissingUserIdException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsApiExceptionToItsDeclaredStatusAndSafeProblemDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets/1");

        ProblemDetail body = handler.handleApiException(
                new MissingUserIdException("Missing required header: X-User-Id"),
                request
        );

        assertThat(body.getStatus()).isEqualTo(401);
        assertThat(body.getTitle()).isEqualTo(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        assertThat(body.getDetail()).isEqualTo("Missing required header: X-User-Id");
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/wallets/1"));
        assertThat(body.getProperties()).containsKey("timestamp");
    }

    @Test
    void usesTheStatusCarriedByEachApiExceptionSubtype() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/intent");

        ProblemDetail body = handler.handleApiException(
                new NotFoundTestException("not here"),
                request
        );

        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/payments/intent"));
    }

    @Test
    void fallsBackToGeneric500WithoutLeakingInternalDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boom");

        ProblemDetail body = handler.handleUnexpected(
                new IllegalStateException("sensitive stack detail"),
                request
        );

        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getDetail()).isEqualTo("Internal server error");
        assertThat(body.getDetail()).doesNotContain("sensitive");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsConstraintViolationTo400WithFieldErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets");

        Path path = mock(Path.class);
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be positive");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ProblemDetail body = handler.handleConstraintViolation(ex, request);

        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/wallets"));
        assertThat(body.getProperties()).containsKey("timestamp");
        assert body.getProperties() != null;
        assertThat((List<String>) body.getProperties().get("errors"))
                .anySatisfy(entry -> assertThat(entry).contains("must be positive"));
    }

    static Stream<Arguments> transientDatabaseFailures() {
        return Stream.of(
                Arguments.of(
                        "a transaction that could not begin (Postgres down, pool timeout)",
                        new CannotCreateTransactionException(
                                "Could not open JPA EntityManager for transaction",
                                new SQLException("Connection refused", "08001")
                        )
                ),
                Arguments.of(
                        "a connection lost mid-transaction",
                        new DataAccessResourceFailureException("could not execute statement")
                ),
                Arguments.of("a query timeout", new QueryTimeoutException("canceling statement")),
                Arguments.of("a lock failure outside the transfer", new PessimisticLockingFailureException("lock")),
                Arguments.of(
                        "a statement Postgres terminated (57P01), as JpaSystemException wraps it",
                        new Uncategorized(new RuntimeException(
                                "could not execute statement",
                                new SQLException("terminating connection due to administrator command", "57P01")
                        ))
                ),
                Arguments.of(
                        "an uncategorised failure whose SQLState is a connection exception",
                        new Uncategorized(new SQLException("An I/O error occurred", "08006"))
                )
        );
    }

    @ParameterizedTest(name = "{0} answers 503")
    @MethodSource("transientDatabaseFailures")
    void aTransientDatabaseFailureAnswers503AskingForARetryWithTheSameKey(String cause, RuntimeException ex) {
        // Guards ADR 0016's 503 remedy: without this mapping a database that is down or restarting answers 500,
        // which tells the client the fault is permanent, although a retry with the same key is safe. The detail
        // must not claim that nothing moved, because a connection lost during the commit leaves that unknown.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/wallets/USD/transfers");

        ProblemDetail body = handler.handleDataAccess(ex, request);

        assertThat(body.getStatus()).isEqualTo(503);
        assertThat(body.getTitle()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        assertThat(body.getDetail()).isEqualTo(GlobalExceptionHandler.DATABASE_UNAVAILABLE);
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/wallets/USD/transfers"));
        assertThat(body.getProperties()).containsKey("timestamp");
    }

    static Stream<Arguments> permanentDataAccessFailures() {
        return Stream.of(
                Arguments.of("a constraint violation", new DataIntegrityViolationException("check violated")),
                Arguments.of("broken SQL", new InvalidDataAccessResourceUsageException("syntax error")),
                Arguments.of(
                        "an uncategorised failure with a non-connection SQLState",
                        new Uncategorized(new SQLException("division by zero", "22012"))
                ),
                Arguments.of("an uncategorised failure without a SQLException", new Uncategorized(null))
        );
    }

    @ParameterizedTest(name = "{0} stays a 500")
    @MethodSource("permanentDataAccessFailures")
    void anyOtherDataAccessFailureStaysAGeneric500(String cause, RuntimeException ex) {
        // Guards the 503 spreading to defects: a retry cannot fix a broken CHECK or bad SQL, and a 503 would
        // invite clients to repeat it.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/wallets/USD/transfers");

        ProblemDetail body = handler.handleDataAccess(ex, request);

        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getDetail()).isEqualTo("Internal server error");
    }

    @Test
    void aParameterOfTheWrongTypeIsNamedButItsValueIsNotQuoted() throws NoSuchMethodException {
        // Guards the framework's default detail, "Failed to convert 'before' with value: '...'", which copies a
        // value of the caller's choosing, line breaks and all, into the response.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets/USD/history");
        MethodParameter parameter = new MethodParameter(Endpoint.class.getDeclaredMethod("history", Long.class), 0);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc\nERROR forged line",
                Long.class,
                "before",
                parameter,
                new NumberFormatException("For input string: \"abc\"")
        );

        ResponseEntity<Object> response = handler.handleTypeMismatch(
                ex,
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request)
        );

        assertThat(response).isNotNull();
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getDetail()).isEqualTo("Invalid value for parameter 'before'");
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/wallets/USD/history"));
        assertThat(body.getProperties()).containsKey("timestamp");
    }

    /**
     * A handler method to take a {@link MethodParameter} from.
     */
    private static final class Endpoint {
        @SuppressWarnings("unused")
        void history(Long before) {
        }
    }

    /**
     * Stands in for {@code JpaSystemException}, which lives in spring-orm, outside the platform's dependencies.
     */
    private static final class Uncategorized extends UncategorizedDataAccessException {
        private Uncategorized(Throwable cause) {
            super("uncategorised", cause);
        }
    }

    /**
     * Local subtype to prove the handler honours whatever status an ApiException declares.
     */
    private static final class NotFoundTestException extends ApiException {
        private NotFoundTestException(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }
}
