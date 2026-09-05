package com.flowwallet.platform.security;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the {@code X-User-Id} header does not yield a usable identity — absent, blank, or longer
 * than a service can store. Maps to HTTP 401 Unauthorized.
 * <p>
 * One status for all three because the caller cannot proceed in any of them, and splitting them would buy
 * nothing it could act on differently.
 */
public class MissingUserIdException extends ApiException {
    public MissingUserIdException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
