package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a provider webhook payload fails signature verification. Maps to HTTP 400 Bad Request:
 * a legitimate provider delivery is always correctly signed, so a bad signature is a malformed or
 * unauthenticated sender, not a server fault.
 */
public class InvalidWebhookSignatureException extends ApiException {
    public InvalidWebhookSignatureException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
