package com.flowwallet.payment.provider.exception;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a webhook cannot be processed on this side, such as a signed event whose data object fails to
 * deserialize after an SDK version mismatch. A redelivery can succeed once that is fixed.
 * See docs/adr/0016-error-model-and-status-codes.md.
 */
public class WebhookProcessingException extends ApiException {
    public WebhookProcessingException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
}
