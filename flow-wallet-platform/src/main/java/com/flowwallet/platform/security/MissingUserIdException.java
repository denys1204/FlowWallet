package com.flowwallet.platform.security;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when the {@code X-User-Id} header does not yield a usable identity. Every cause maps to 401, because the
 * caller can act on none of them differently. See docs/adr/0003-caller-identity-and-trust-boundary.md.
 */
public class MissingUserIdException extends ApiException {
    public MissingUserIdException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
