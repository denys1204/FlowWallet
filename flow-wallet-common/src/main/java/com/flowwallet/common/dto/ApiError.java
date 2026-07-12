package com.flowwallet.common.dto;

import java.time.Instant;

/**
 * Standardized error response returned by all services.
 *
 * @param status    HTTP status code
 * @param message   human-readable error message
 * @param path      request URI that caused the error
 * @param timestamp when the error occurred
 */
public record ApiError(
        int status,
        String message,
        String path,
        Instant timestamp
) {}
