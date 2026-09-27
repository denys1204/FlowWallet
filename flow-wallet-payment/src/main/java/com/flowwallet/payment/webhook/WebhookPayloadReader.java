package com.flowwallet.payment.webhook;

import com.flowwallet.payment.config.PaymentWebhookProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads a webhook body up to {@code payment.webhook.max-payload-size}. The route is public and unauthenticated,
 * and the signature can only be checked over the whole body, so the size is bounded before anything else looks
 * at it. See docs/adr/0017-webhooks-verified-before-they-are-read.md.
 */
@Component
@RequiredArgsConstructor
public class WebhookPayloadReader {
    private final PaymentWebhookProperties properties;

    /**
     * A declared {@code Content-Length} over the limit is refused unread. A chunked body declares none, so at most
     * one byte past the limit is read to tell the two apart.
     *
     * @throws WebhookPayloadTooLargeException if the body is larger than the limit
     */
    public String read(HttpServletRequest request) {
        int limit = Math.toIntExact(properties.getMaxPayloadSize().toBytes());
        if (request.getContentLengthLong() > limit) {
            throw new WebhookPayloadTooLargeException(limit);
        }

        byte[] body;
        try {
            body = request.getInputStream().readNBytes(limit + 1);
        } catch (IOException e) {
            // The same answer Spring MVC gives when it fails to read a @RequestBody.
            throw new HttpMessageNotReadableException(
                    "I/O error while reading the webhook body",
                    e,
                    new ServletServerHttpRequest(request)
            );
        }
        if (body.length > limit) {
            throw new WebhookPayloadTooLargeException(limit);
        }
        // Stripe signs the UTF-8 bytes, and stripe-java encodes the string back to UTF-8 before it computes the HMAC.
        return new String(body, StandardCharsets.UTF_8);
    }
}
