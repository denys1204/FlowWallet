package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a webhook's signature is missing, malformed or invalid, and for every webhook while no signing secret
 * is configured. A real delivery is always signed, so a bad signature is the sender's fault.
 * See docs/adr/0016-error-model-and-status-codes.md and docs/adr/0017-webhooks-verified-before-they-are-read.md.
 */
public class InvalidWebhookSignatureException extends ApiException {
    public InvalidWebhookSignatureException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
