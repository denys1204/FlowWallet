package com.flowwallet.platform.web;

import com.flowwallet.platform.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Renders every error of a servlet service as an RFC 9457 {@code application/problem+json} response; registered
 * by {@link WebExceptionHandlerAutoConfiguration}. {@link ResponseEntityExceptionHandler} already types the
 * standard Spring MVC exceptions, and every problem, the framework's included, gets a {@code timestamp} and the
 * request path as {@code instance}. See docs/adr/0016-error-model-and-status-codes.md.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    static final String DATABASE_UNAVAILABLE =
            "Service temporarily unavailable; retry the request, with the same Idempotency-Key if it has one";

    private static final String INTERNAL_ERROR = "Internal server error";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex, HttpServletRequest request) {
        HttpStatus status = ex.getStatus();
        if (status.is5xxServerError()) {
            log.error("API exception {} at {}: {}", status.value(), request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.warn("API exception {} at {}: {}", status.value(), request.getRequestURI(), ex.getMessage());
        }
        return problem(status, ex.getMessage(), request.getRequestURI());
    }

    /**
     * Method validation of path, query and header parameters. It arrives here only from a controller annotated
     * {@code @Validated}; without it Spring MVC raises {@code HandlerMethodValidationException} and the problem
     * carries no {@code errors}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Request validation failed", request.getRequestURI());
        body.setProperty("errors", ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .toList());
        return body;
    }

    /**
     * A database the service cannot reach, or one that ended the connection or the statement, answers 503 on every
     * endpoint: a retry with the same key finds the operation done or does it once. The detail claims nothing
     * about the outcome, because a connection lost during the commit leaves it unknown. Any other data access
     * failure stays a 500. See docs/adr/0025-unreachable-database-answers-503.md.
     */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ProblemDetail handleDataAccess(RuntimeException ex, HttpServletRequest request) {
        if (!isTransientDatabaseFailure(ex)) {
            return handleUnexpected(ex, request);
        }
        log.error("Database unavailable at {}", request.getRequestURI(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, DATABASE_UNAVAILABLE, request.getRequestURI());
    }

    /**
     * Last resort: the cause goes to the log and never into the detail.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, request.getRequestURI());
    }

    /**
     * Request-body validation ({@code @Valid @RequestBody}): lists the failing fields in {@code errors}.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request
    ) {
        ProblemDetail body = ex.getBody();
        body.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .toList());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Enriches every problem the base class builds. The {@code @ExceptionHandler} methods above bypass it and
     * enrich through {@code problem}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (body instanceof ProblemDetail pd) {
            enrich(pd, path(request));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /**
     * Postgres down, a pool that timed out, a connection lost mid-transaction, or a server shutting down: Spring
     * reports a failed begin as {@link CannotCreateTransactionException}, a lost connection as
     * {@link DataAccessResourceFailureException}, and a statement Postgres terminated (SQLState 57P01) as a
     * {@code JpaSystemException}, whose only mark is the SQLState of the {@link SQLException} underneath.
     */
    private static boolean isTransientDatabaseFailure(Throwable ex) {
        if (ex instanceof CannotCreateTransactionException
                || ex instanceof TransientDataAccessException
                || ex instanceof DataAccessResourceFailureException) {
            return true;
        }
        return ex instanceof DataAccessException && hasConnectionSqlState(ex);
    }

    /**
     * SQLState class 08 is a connection exception; 57P01 to 57P05 are Postgres ending the session (an
     * administrator or crash shutdown, a server not accepting connections yet, a dropped database, an idle
     * timeout).
     */
    private static boolean hasConnectionSqlState(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("57P0"))) {
                return true;
            }
        }
        return false;
    }

    private ProblemDetail problem(HttpStatus status, String detail, String path) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        enrich(pd, path);
        return pd;
    }

    private void enrich(ProblemDetail pd, String path) {
        if (pd.getInstance() == null && path != null) {
            pd.setInstance(URI.create(path));
        }
        Map<String, Object> properties = pd.getProperties();
        if (properties == null || !properties.containsKey("timestamp")) {
            pd.setProperty("timestamp", Instant.now());
        }
    }

    private String path(WebRequest request) {
        return (request instanceof ServletWebRequest servletWebRequest)
                ? servletWebRequest.getRequest().getRequestURI()
                : null;
    }
}
