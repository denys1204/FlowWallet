package com.flowwallet.platform.security;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the {@code X-User-Id} header is missing or blank. Maps to HTTP 401 Unauthorized.
 */
public class MissingUserIdException extends ApiException {
    public MissingUserIdException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
