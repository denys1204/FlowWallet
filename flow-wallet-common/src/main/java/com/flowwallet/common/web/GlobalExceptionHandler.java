package com.flowwallet.common.web;

import com.flowwallet.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Shared exception handler that renders RFC 9457 {@code application/problem+json} responses for every
 * service.
 * <p>
 * Auto-registered for servlet web applications via {@link WebExceptionHandlerAutoConfiguration}, so any
 * service depending on {@code flow-wallet-common} gets consistent error responses without declaring its
 * own advice. Extends {@link ResponseEntityExceptionHandler} so standard Spring MVC exceptions
 * (unsupported method, unreadable body, ...) already produce a correctly-typed {@link ProblemDetail};
 * this class only enriches every problem with a {@code timestamp} and the request path as {@code instance}.
 * Returning a {@link ProblemDetail} lets Spring set both the HTTP status and the {@code problem+json}
 * content type.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Any domain exception that declares its own HTTP status. */
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

    /** Constraint violations on path/query params (method-level validation). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Request validation failed", request.getRequestURI());
        body.setProperty("errors", ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .toList());
        return body;
    }

    /** Last-resort handler: never leak internal details, always log the cause. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request.getRequestURI());
    }

    /** Request-body validation (@Valid @RequestBody) — enrich the framework problem with field errors. */
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

    /** Enrich every problem+json body (ours and the framework's) with a timestamp and request path. */
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
