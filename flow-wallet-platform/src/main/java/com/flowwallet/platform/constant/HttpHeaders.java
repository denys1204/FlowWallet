package com.flowwallet.platform.constant;

/**
 * HTTP header names used for inter-service user identity propagation.
 */
public final class HttpHeaders {
    private HttpHeaders() {
    }

    /**
     * Header carrying the caller's user id, read through {@code @CurrentUserId}.
     */
    public static final String USER_ID = "X-User-Id";
}
