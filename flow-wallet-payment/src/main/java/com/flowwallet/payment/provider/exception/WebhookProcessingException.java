package com.flowwallet.payment.provider.exception;

import com.flowwallet.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a validly-signed webhook payload cannot be processed (e.g. its data object cannot be
 * deserialized). Maps to HTTP 500: the signature was valid, so this is a server-side / SDK-version fault
 * rather than a bad request. The detail is developer-authored and safe; the cause is logged.
 */
public class WebhookProcessingException extends ApiException {
    public WebhookProcessingException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
}
