package com.flowwallet.common.security;

/**
 * Thrown when the {@code X-User-Id} header is missing or blank.
 * <p>
 * Should be mapped to HTTP 401 Unauthorized by the global exception handler.
 */
public class MissingUserIdException extends RuntimeException {
    public MissingUserIdException(String message) {
        super(message);
    }
}
