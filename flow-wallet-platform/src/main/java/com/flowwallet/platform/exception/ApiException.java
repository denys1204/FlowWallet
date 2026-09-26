package com.flowwallet.platform.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base type for exceptions that carry the HTTP status they map to; {@code GlobalExceptionHandler} renders them as
 * RFC 9457 problems.
 * <p>
 * The message becomes the problem {@code detail} and is also logged, so it is written for the caller and never
 * carries a balance figure or a rejected header value. See docs/adr/0016-error-model-and-status-codes.md.
 */
@Getter
public abstract class ApiException extends RuntimeException {
    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    protected ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
