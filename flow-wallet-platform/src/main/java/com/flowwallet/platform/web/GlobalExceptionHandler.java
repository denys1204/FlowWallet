package com.flowwallet.platform.web;

import com.flowwallet.platform.exception.ApiException;
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
 * Renders every error of a servlet service as an RFC 9457 {@code application/problem+json} response; registered
 * by {@link WebExceptionHandlerAutoConfiguration}. {@link ResponseEntityExceptionHandler} already types the
 * standard Spring MVC exceptions, and every problem, the framework's included, gets a {@code timestamp} and the
 * request path as {@code instance}. See docs/adr/0016-error-model-and-status-codes.md.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
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
     * Last resort: the cause goes to the log and never into the detail.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request.getRequestURI());
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
