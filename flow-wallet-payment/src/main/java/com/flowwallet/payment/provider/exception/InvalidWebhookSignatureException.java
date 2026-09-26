package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a webhook's signature is missing or invalid. A real delivery is always signed, so the fault is the
 * sender's. See docs/adr/0016-error-model-and-status-codes.md.
 */
public class InvalidWebhookSignatureException extends ApiException {
    public InvalidWebhookSignatureException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
