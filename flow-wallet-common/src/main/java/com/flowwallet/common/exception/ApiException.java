package com.flowwallet.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base type for exceptions that carry the HTTP status they should map to.
 * <p>
 * The shared {@code GlobalExceptionHandler} renders any {@code ApiException} as an RFC 9457
 * {@code application/problem+json} response using {@link #getStatus()}, so domain code can throw a
 * meaningful exception instead of hard-coding status codes in controllers. Messages of
 * {@code ApiException}s are considered safe to expose to clients as the problem {@code detail}.
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
