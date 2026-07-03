package com.flowwallet.common.constant;

/**
 * HTTP header names used for inter-service user identity propagation.
 */
public final class HttpHeaders {
    private HttpHeaders() {
        // utility class — no instantiation
    }

    /**
     * Header carrying the authenticated user's ID.
     * <p>
     * Set by API Gateway (from JWT in production, passed through in showcase mode).
     * Read by downstream services via {@code @CurrentUserId}.
     */
    public static final String USER_ID = "X-User-Id";
}
