package com.flowwallet.payment.webhook;

import com.flowwallet.platform.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a webhook body exceeds {@code payment.webhook.max-payload-size}. It is refused before its signature
 * is checked. See docs/adr/0017-webhooks-verified-before-they-are-read.md.
 */
public class WebhookPayloadTooLargeException extends ApiException {
    public WebhookPayloadTooLargeException(long limitBytes) {
        super(HttpStatus.CONTENT_TOO_LARGE, "Webhook body exceeds %d bytes".formatted(limitBytes));
    }
}
